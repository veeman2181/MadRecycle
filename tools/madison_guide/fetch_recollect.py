"""
Pulls disposal-guidance text for this app's known MaterialType categories from the City of
Madison's public recycling-guide widget (Recollect), and writes a local snapshot a developer can
read and manually merge into app/src/main/assets/seed_recyclables.json.

This deliberately does NOT touch seed_recyclables.json automatically. Recollect's text only ever
supplies the *secondary detail* line (RecyclableItem.rulesText) -- whether a category keeps its
existing requiresFlatten/requires3D/isRecyclableAsIs flags and headline RuleMessage is an editorial
call (see the "keep 3D" rule, which is Pellitteri-sorter-specific and isn't in Recollect's own
consumer text at all), not something safe to regenerate blindly.

Why this exists as a standalone dev script rather than a client-side fetch:
- api.recollect.net is an undocumented widget API (no published developer terms, no auth/API key)
  backing the city's own site. Running this occasionally from a developer machine is a handful of
  requests; the Android app calling it directly would mean every installed device hitting a
  third-party endpoint with unknown ToS, and the URL sitting decompilable in a shipped APK.
- The app is offline-first by design (SPEC.md NFR-1) -- shipping a bundled/seeded asset that a
  developer refreshes occasionally fits that better than a live runtime dependency anyway.

Usage:
    python fetch_recollect.py

Writes: tools/madison_guide/recollect_snapshot.json
"""
import json
import pathlib
import re
import time
import urllib.request

AREA = "CityofMadisonWI"
SERVICE_ID = "1567"
BASE_URL = f"https://api.recollect.net/api/areas/{AREA}/services/{SERVICE_ID}/pages"
PAGE_SIZE = 100
MAX_PAGES = 20  # safety cap: 2000 items: comfortably covers this service's ~800-item catalog
REQUEST_DELAY_SECONDS = 0.5  # be a polite, infrequent caller -- this is a dev tool, not a service client

OUTPUT_PATH = pathlib.Path(__file__).parent / "recollect_snapshot.json"

# This app's MaterialType -> the Recollect page_name(s) that best represent that coarse category.
# Found via the `suggest=<query>` search endpoint (e.g. suggest=cardboard), then confirmed against
# the bulk listing. Multiple page_names for one MaterialType (e.g. milk vs. juice cartons) get
# merged, first-non-empty-instruction-wins.
TARGET_PAGE_NAMES = {
    "CARDBOARD": ["corrugated_cardboard"],
    "PLASTIC_JUG": ["plastic_bottle"],
    "METAL_CAN": ["aluminum_can", "metal_food_cans"],
    "DRINK_CARTON": ["milk_carton", "gable_top_carton", "aseptic_carton"],
    "GLASS": ["glass_bottle", "glass_jar"],
    "PAPER": ["paper"],
}
ALL_TARGET_PAGE_NAMES = {name for names in TARGET_PAGE_NAMES.values() for name in names}


def strip_html(html):
    if not html:
        return ""
    text = re.sub(r"<[^>]+>", " ", html)
    text = text.replace("&nbsp;", " ").replace("&#39;", "'").replace("&amp;", "&")
    return re.sub(r"\s+", " ", text).strip()


def fetch_batch(offset):
    url = f"{BASE_URL}?type=material&set=default&locale=en-US&accept_list=true&offset={offset}&limit={PAGE_SIZE}"
    with urllib.request.urlopen(url, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


def extract_entry(item):
    variables = {v.get("name"): v for v in item.get("opts", {}).get("variables", [])}

    def text_of(name):
        value = variables.get(name, {}).get("value", {})
        return strip_html(value.get("en-US") or value.get("en") or "")

    return {
        "page_name": item.get("page_name"),
        "title": text_of("title") or item.get("page_name"),
        "synonyms": text_of("synonyms"),
        "special_instructions": text_of("special_instructions"),
        "dropoff_instructions": text_of("dropoff_instructions"),
    }


def main():
    found_by_page_name = {}
    remaining = set(ALL_TARGET_PAGE_NAMES)

    for page_num in range(MAX_PAGES):
        if not remaining:
            break
        offset = page_num * PAGE_SIZE
        print(f"Fetching offset={offset} (looking for: {sorted(remaining)})")
        batch = fetch_batch(offset)
        if not batch:
            print("Reached end of catalog before finding every target page_name.")
            break
        for item in batch:
            page_name = item.get("page_name")
            if page_name in remaining:
                found_by_page_name[page_name] = extract_entry(item)
                remaining.discard(page_name)
        time.sleep(REQUEST_DELAY_SECONDS)

    if remaining:
        print(f"WARNING: never found these page_names (site content may have moved): {sorted(remaining)}")

    snapshot = {
        "source": BASE_URL,
        "note": "Unofficial widget API behind the city's own recycling guide page. "
                "Refresh this snapshot occasionally by re-running this script; do not call "
                "this endpoint from the shipped app.",
        "materials": {},
    }
    for material_type, page_names in TARGET_PAGE_NAMES.items():
        entries = [found_by_page_name[p] for p in page_names if p in found_by_page_name]
        # First page_name with actual instruction text wins; several categories (glass, cartons)
        # have no item-specific override at all in Recollect's data -- that's a real finding, not
        # a fetch failure, so it's recorded as an empty string rather than omitted.
        best = next((e for e in entries if e["special_instructions"] or e["dropoff_instructions"]), entries[0] if entries else None)
        snapshot["materials"][material_type] = {
            "checked_page_names": page_names,
            "matched": entries,
            "suggested_rules_text": (best["special_instructions"] or best["dropoff_instructions"]) if best else "",
        }

    OUTPUT_PATH.write_text(json.dumps(snapshot, indent=2))
    print(f"\nWrote {OUTPUT_PATH}")
    print("\nSuggested rulesText per category (review before merging into seed_recyclables.json):")
    for material_type, data in snapshot["materials"].items():
        print(f"  {material_type}: {data['suggested_rules_text']!r}")


if __name__ == "__main__":
    main()
