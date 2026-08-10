"""
AWS Lambda proxy for the app's cloud vision tier (§5.5 Tier 3.5 -- now the primary
CV resolver, with the on-device classifier demoted to fallback; see
CloudVisionMaterialTierImpl.kt).

Exists solely so the Anthropic API key never ships inside the Android APK -- the app
calls this Lambda's Function URL with a shared secret instead of calling Anthropic
directly. The shared secret is still bundled in the app and therefore extractable, but
a leaked shared secret only lets someone hit this proxy (which you can rotate, rate-limit,
and monitor); it cannot run up charges beyond whatever spend limit you set on the
Anthropic API key itself. That's the residual risk this design accepts, not a full fix.

Request:  POST with header 'X-Proxy-Secret: <shared secret>' and JSON body
          {"image_base64": "<jpeg bytes, base64>"}
Response: 200 {"material_type": "PLASTIC_JUG", "product_category": "PLASTIC_DETERGENT_BOTTLES", "confidence": 0.92}
          "product_category" is "NONE" (not null) when no confident product-level guess applies --
          keeps the JSON Schema enum-only, no separate null type.
"""
import base64
import json
import os

import anthropic

ANTHROPIC_API_KEY = os.environ["ANTHROPIC_API_KEY"]
PROXY_SHARED_SECRET = os.environ["PROXY_SHARED_SECRET"]

# Keep in sync with MaterialType.kt and ProductCategory.kt.
MATERIAL_TYPES = [
    "CARDBOARD", "PLASTIC_JUG", "METAL_CAN", "DRINK_CARTON",
    "PLASTIC_FILM", "GLASS", "PAPER", "OTHER",
]
PRODUCT_CATEGORIES = [
    "NONE",
    "AEROSOL_CANS", "ALUMINUM_SODA_CANS", "FOOD_CAN",
    "GLASS_BEVERAGE_BOTTLES", "GLASS_COSMETIC_CONTAINERS", "GLASS_FOOD_JARS",
    "MAGAZINES", "NEWSPAPER", "OFFICE_PAPER", "PAPER_CUPS",
    "PLASTIC_DETERGENT_BOTTLES", "PLASTIC_FOOD_CONTAINERS",
    "PLASTIC_SHOPPING_BAGS", "PLASTIC_SODA_BOTTLES",
    "PLASTIC_TRASH_BAGS", "PLASTIC_WATER_BOTTLES",
]

SYSTEM_PROMPT = f"""You classify a single photographed item for a curbside recycling app
serving Madison, Wisconsin. Identify the material category and, when confident, the
specific product type -- the app shows the product name to the user to build trust in
the disposal instructions it then gives from material_type alone.

material_type must be exactly one of: {", ".join(MATERIAL_TYPES)}.
Use OTHER for anything not accepted in Madison's curbside recycling (food waste,
clothing, styrofoam, electronics, tools, office supplies, cosmetics, or any object you
don't recognize as one of the other categories) -- OTHER is not a last resort to avoid,
it is the correct answer for most everyday objects, which are not recyclable containers.

product_category must be exactly one of: {", ".join(PRODUCT_CATEGORIES)}, or "NONE" if
no specific product-level category fits confidently (always "NONE" when material_type
is OTHER or CARDBOARD -- cardboard has no product-level subtype in this taxonomy).

confidence is your own calibrated confidence (0.0-1.0) in material_type specifically --
it is fine for this to be high even when product_category is "NONE"."""

RESPONSE_SCHEMA = {
    "type": "object",
    "properties": {
        "material_type": {"type": "string", "enum": MATERIAL_TYPES},
        "product_category": {"type": "string", "enum": PRODUCT_CATEGORIES},
        "confidence": {"type": "number"},
    },
    "required": ["material_type", "product_category", "confidence"],
    "additionalProperties": False,
}

client = anthropic.Anthropic(api_key=ANTHROPIC_API_KEY)


def handler(event, context):
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    if headers.get("x-proxy-secret") != PROXY_SHARED_SECRET:
        return _response(401, {"error": "unauthorized"})

    try:
        body = json.loads(event.get("body") or "{}")
        image_b64 = body["image_base64"]
    except (json.JSONDecodeError, KeyError):
        return _response(400, {"error": "expected JSON body with an image_base64 field"})

    # Cap request size before it ever reaches Anthropic -- 4MB covers a compressed
    # camera-frame JPEG many times over; anything bigger is either misuse or a bug.
    if len(image_b64) > 6_000_000:
        return _response(413, {"error": "image_base64 too large"})

    try:
        message = client.messages.create(
            model="claude-haiku-4-5",
            max_tokens=256,
            system=SYSTEM_PROMPT,
            messages=[{
                "role": "user",
                "content": [
                    {
                        "type": "image",
                        "source": {"type": "base64", "media_type": "image/jpeg", "data": image_b64},
                    },
                    {"type": "text", "text": "What is this item?"},
                ],
            }],
            output_config={"format": {"type": "json_schema", "schema": RESPONSE_SCHEMA}},
        )
    except anthropic.APIStatusError as e:
        # Refusal/rate-limit/etc. -- the app falls back to the on-device classifier on
        # any non-200, so surface the status rather than masking it as a 500.
        return _response(502, {"error": f"anthropic_api_error: {e.status_code}"})

    text = next(block.text for block in message.content if block.type == "text")
    return _response(200, json.loads(text))


def _response(status, body):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body),
    }
