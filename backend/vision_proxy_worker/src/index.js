/**
 * Cloudflare Worker proxy for the app's cloud vision tier (see
 * CloudVisionMaterialTierImpl.kt and ScanPipelineCoordinator for why the
 * on-device classifier is the fallback rather than the other way around).
 *
 * Exists solely so the Anthropic API key never ships inside the Android APK --
 * the app calls this Worker with a shared secret instead of calling Anthropic
 * directly. The shared secret is still bundled in the app and therefore
 * extractable, but a leaked shared secret only lets someone hit this proxy
 * (which you can rotate, rate-limit, and monitor); it cannot run up charges
 * beyond whatever spend limit you set on the Anthropic API key itself.
 *
 * Request:  POST with header 'X-Proxy-Secret: <shared secret>' and JSON body
 *           {"image_base64": "<jpeg bytes, base64>"}
 * Response: 200 {"material_type": "PLASTIC_JUG", "product_category": "PLASTIC_DETERGENT_BOTTLES", "confidence": 0.92}
 *           "product_category" is "NONE" (not null) when no confident product-level guess applies --
 *           keeps the JSON Schema enum-only, no separate null type.
 *
 * Ported from the original AWS Lambda implementation (backend/vision_proxy) --
 * moved here to avoid AWS's IAM/OIDC/Lambda-Function-URL auth machinery, which
 * this account hit an unresolved account-level restriction on.
 */

// Keep in sync with MaterialType.kt and ProductCategory.kt.
const MATERIAL_TYPES = [
  "CARDBOARD", "PLASTIC_JUG", "METAL_CAN", "DRINK_CARTON",
  "PLASTIC_FILM", "GLASS", "PAPER", "OTHER",
];
const PRODUCT_CATEGORIES = [
  "NONE",
  "AEROSOL_CANS", "ALUMINUM_SODA_CANS", "FOOD_CAN",
  "GLASS_BEVERAGE_BOTTLES", "GLASS_COSMETIC_CONTAINERS", "GLASS_FOOD_JARS",
  "MAGAZINES", "NEWSPAPER", "OFFICE_PAPER", "PAPER_CUPS",
  "PLASTIC_DETERGENT_BOTTLES", "PLASTIC_FOOD_CONTAINERS",
  "PLASTIC_SHOPPING_BAGS", "PLASTIC_SODA_BOTTLES",
  "PLASTIC_TRASH_BAGS", "PLASTIC_WATER_BOTTLES",
];

const SYSTEM_PROMPT = `You classify a single photographed item for a curbside recycling app
serving Madison, Wisconsin. Identify the material category and, when confident, the
specific product type -- the app shows the product name to the user to build trust in
the disposal instructions it then gives from material_type alone.

material_type must be exactly one of: ${MATERIAL_TYPES.join(", ")}.
Use OTHER for anything not accepted in Madison's curbside recycling (food waste,
clothing, styrofoam, electronics, tools, office supplies, cosmetics, or any object you
don't recognize as one of the other categories) -- OTHER is not a last resort to avoid,
it is the correct answer for most everyday objects, which are not recyclable containers.

product_category must be exactly one of: ${PRODUCT_CATEGORIES.join(", ")}, or "NONE" if
no specific product-level category fits confidently (always "NONE" when material_type
is OTHER or CARDBOARD -- cardboard has no product-level subtype in this taxonomy).

confidence is your own calibrated confidence (0.0-1.0) in material_type specifically --
it is fine for this to be high even when product_category is "NONE".`;

const RESPONSE_SCHEMA = {
  type: "object",
  properties: {
    material_type: { type: "string", enum: MATERIAL_TYPES },
    product_category: { type: "string", enum: PRODUCT_CATEGORIES },
    confidence: { type: "number" },
  },
  required: ["material_type", "product_category", "confidence"],
  additionalProperties: false,
};

// Cap request size before it ever reaches Anthropic -- 4MB covers a compressed
// camera-frame JPEG many times over; anything bigger is either misuse or a bug.
const MAX_IMAGE_BASE64_LENGTH = 6_000_000;

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return jsonResponse(405, { error: "method not allowed" });
    }

    const secret = request.headers.get("X-Proxy-Secret");
    if (secret !== env.PROXY_SHARED_SECRET) {
      return jsonResponse(401, { error: "unauthorized" });
    }

    let imageBase64;
    try {
      const body = await request.json();
      imageBase64 = body.image_base64;
      if (typeof imageBase64 !== "string") throw new Error("missing image_base64");
    } catch {
      return jsonResponse(400, { error: "expected JSON body with an image_base64 field" });
    }

    if (imageBase64.length > MAX_IMAGE_BASE64_LENGTH) {
      return jsonResponse(413, { error: "image_base64 too large" });
    }

    const anthropicResponse = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: "claude-haiku-4-5",
        max_tokens: 256,
        system: SYSTEM_PROMPT,
        messages: [{
          role: "user",
          content: [
            {
              type: "image",
              source: { type: "base64", media_type: "image/jpeg", data: imageBase64 },
            },
            { type: "text", text: "What is this item?" },
          ],
        }],
        output_config: { format: { type: "json_schema", schema: RESPONSE_SCHEMA } },
      }),
    });

    if (!anthropicResponse.ok) {
      // Refusal/rate-limit/etc. -- the app falls back to the on-device classifier on
      // any non-200, so surface the status rather than masking it as a 500.
      return jsonResponse(502, { error: `anthropic_api_error: ${anthropicResponse.status}` });
    }

    const message = await anthropicResponse.json();
    const textBlock = message.content.find((block) => block.type === "text");
    // output_config.format guarantees textBlock.text is valid JSON matching RESPONSE_SCHEMA.
    return new Response(textBlock.text, {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  },
};
