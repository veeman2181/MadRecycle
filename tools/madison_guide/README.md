# Madison recycling-guide snapshot

Pulls disposal-guidance text for this app's `MaterialType` categories from the City of Madison's
own recycling guide (the API backing https://www.cityofmadison.com/streets/trash-recycling/guidelines/recycling),
and writes a local, checked-in snapshot at `recollect_snapshot.json`.

## Why a dev-side script instead of a live app dependency

`api.recollect.net` is an unofficial widget API: no published developer docs, no auth/API key,
and no stated terms of service for third-party use. It's what the city's own page calls to power
its "search for a material" tool.

- Running this script occasionally from a developer machine is a handful of requests.
- Having the shipped Android app call it directly instead would mean every installed device
  hitting a third-party endpoint with unknown ToS, with the URL sitting decompilable in the APK.
- The app is offline-first by design (SPEC.md NFR-1) — a bundled asset refreshed occasionally by
  a developer fits that better than a runtime dependency on someone else's undocumented API.

The app itself never calls Recollect. This script is the only thing that does, and only when a
developer runs it manually.

## What it fetches

Only the specific categories this app cares about (`TARGET_PAGE_NAMES` in `fetch_recollect.py`),
found via Recollect's `suggest=<query>` search endpoint and confirmed against the bulk listing —
not a scrape of the city's full multi-hundred-item catalog (mattresses, hazardous waste, large
items, etc., none of which fit this app's camera-scan pipeline).

## Usage

```
python fetch_recollect.py
```

Writes `recollect_snapshot.json` and prints a suggested `rulesText` per category to stdout.

## Merging into the app

This script does **not** touch `app/src/main/assets/seed_recyclables.json` automatically.
`rulesText` there is a secondary detail line shown under the primary rule message — merge new
text in by hand after reviewing it, since:

- Some categories (`GLASS`, `DRINK_CARTON` as of the last run) have no item-specific override in
  Recollect's data at all — that's a real finding, not a bug, and means no detail line is shown.
- This app's headline rules for jugs/cans/cartons ("Keep 3D — do not crush") and cardboard
  ("Flatten") come from Pellitteri Systems' sorting-facility requirements, not from Recollect's
  consumer-facing text — Recollect doesn't mention sorter-jamming at all. Never let a refresh of
  this snapshot silently overwrite those headline rules or their `requiresFlatten`/`requires3D`
  flags; only the secondary `rulesText` detail should come from this source.
