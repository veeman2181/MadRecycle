# EcoMadison — Technical Specification

Status: Draft v0.1
Source: EcoMadison PRD
Scope: Android app (Kotlin/Compose), local ML pipeline, hardware anchors (BLE/NFC), B2B sync backend

This document restates the PRD as implementable, testable requirements. Each functional requirement has a stable ID, explicit trigger/behavior, and acceptance criteria. Sections map 1:1 to PRD sections where useful, but content is organized for engineering execution, not stakeholder narrative.

---

## 1. System Overview

EcoMadison is an offline-first Android app that (a) tells a resident how to dispose of a scanned item under Madison-specific recycling rules, (b) fires that guidance automatically when the resident is physically near a landlord-installed hardware anchor (BLE beacon or NFC tag), and (c) logs verified compliant disposals to a per-property ledger that landlords use for legal notice compliance and rent-credit rewards.

Three subsystems, independently testable:

1. **Local Rules Engine** — barcode/object/OCR pipeline → Room DB lookup → on-screen disposal instruction. Must work with zero network.
2. **Hardware Anchor Layer** — BLE background scan + NFC NDEF intent → triggers/launches the scanner at the point of disposal.
3. **B2B Sync Layer** — property code binding, points ledger, compliance log, batched upload to landlord backend.

---

## 2. Architecture

```
[ UI: Jetpack Compose ]
        │
[ ViewModel (MVI) ]
        │
[ Repository (single source of truth) ]
   ├── Room DB (rules cache, scan log, points ledger)   — offline source of truth
   └── Retrofit/Ktor client (rules sync, points sync, property registration)
```

- **UI**: Jetpack Compose only. No XML views.
- **State**: MVI — one `UiState` data class per screen, one `Intent`/`Event` sealed class per screen, unidirectional flow via `StateFlow`.
- **Concurrency**: Kotlin Coroutines + Flow. No callbacks, no RxJava.
- **DI**: Hilt. One `@Module` per subsystem (Database, Network, Hardware, ML).
- **Persistence**: Room (SQLite). Encrypted at rest via SQLCipher for tables containing user ID or scan history (see §7.4).
- **Networking**: Ktor client, JSON (kotlinx.serialization), TLS 1.2+ only.
- **ML**: Google ML Kit — Barcode Scanning, Object Detection & Tracking (on-device/base model), Text Recognition (Latin) — plus a custom on-device TFLite material classifier (§5.5 Tier 2.5) fine-tuned on TrashNet. The pipeline is offline-first by design (scans happen in basements/interiors with unreliable connectivity); an optional, network-gated, non-blocking cloud vision backup (§5.5 Tier 3.5) is wired into the pipeline but currently stubbed pending a vendor/API-key decision — see §5.5 for the exact gating contract.
- **Hardware**: `BluetoothLeScanner` (background scan), `NfcAdapter` (NDEF foreground dispatch + intent filter).

Non-goals: iOS client, cloud-only (non-cached) rule lookups, general-purpose (non-Madison) recycling rules.

---

## 3. Data Model

### 3.1 Room Schema

```kotlin
@Entity(tableName = "madison_recyclables")
data class RecyclableItem(
    @PrimaryKey val barcode: String,          // UPC-A/E or EAN; "" for OCR/manual-fallback entries keyed by materialType
    val itemName: String,
    val materialType: MaterialType,           // enum: CARDBOARD, PLASTIC_JUG, METAL_CAN, DRINK_CARTON, PLASTIC_FILM, OTHER
    val rulesText: String,                    // human-readable rule shown in UI
    val minDimensionInches: Float?,           // null if not size-gated
    val requiresFlatten: Boolean,             // true for cardboard
    val requires3D: Boolean,                  // true for jugs/cans/cartons
    val lastUpdatedTimestamp: Long            // epoch millis; drives cache staleness check
)

@Entity(tableName = "property_org")
data class PropertyOrg(
    @PrimaryKey val propertyCode: String,     // e.g. "MPM-OAK-302"
    val landlordId: String,
    val displayName: String,
    val boundAtTimestamp: Long
)

@Entity(tableName = "scan_log")
data class ScanLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val propertyCode: String,
    val barcode: String?,
    val materialType: MaterialType,
    val resolvedByTier: Int,                  // 1=barcode, 2=object, 3=ocr, 4=manual
    val anchorId: String?,                    // BLE UUID or NFC tag ID that authorized this scan; null = anchor-fallback path (§5.6)
    val attestationPhotoUri: String?,         // required for point award, see REQ-4.6.1
    val pointsAwarded: Int,
    val timestamp: Long,
    val syncStatus: SyncStatus                // PENDING, SYNCED, FAILED
)

@Entity(tableName = "points_ledger")
data class PointsLedgerEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val propertyCode: String,
    val points: Int,
    val monthYear: String,                    // "2026-08", for monthly threshold rollup
    val syncStatus: SyncStatus
)
```

Indices: `scan_log(userId, timestamp)`, `scan_log(syncStatus)`, `points_ledger(userId, monthYear)`.

### 3.2 Cache Staleness Policy

- `isCacheExpired()` returns true if `now - max(lastUpdatedTimestamp across all rows) > 24h`.
- Sync is attempted at most once per 24h per app-foreground session; never blocks the read path (see §4.2 flow).

---

## 4. Repository / Sync Contract

### 4.1 Rule Lookup (Read Path)

```kotlin
fun getDisposalRule(barcode: String): Flow<RecyclableItem?>
```

Behavior:
1. Emit cached `RecyclableItem` from Room immediately (may be `null` if unknown barcode — triggers Tier 2/3/4 pipeline, not an error state).
2. If `isCacheExpired()`, fetch `GET /v1/rules/madison` in background, upsert via `insertAll`, do not re-emit unless the resolved item's row changed (avoid UI flicker).
3. Network failure on step 2 is swallowed — log locally, no user-facing error, no retry storm (exponential backoff, max 1 retry per session).

### 4.2 Backend API Contract (v1)

| Endpoint | Method | Auth | Purpose |
|---|---|---|---|
| `/v1/rules/madison` | GET | API key (app-level) | Returns full/delta `RecyclableItem[]` list, `updatedSince` query param supported |
| `/v1/property/{code}/bind` | POST | User JWT | Validates property code exists, binds `userId` → `propertyCode`, returns landlord display config |
| `/v1/property/{code}/points` | POST | User JWT | Batched upload of `PointsLedgerEntry[]` and `ScanLogEntry[]` since last sync |
| `/v1/property/{code}/compliance-log` | GET | Landlord JWT | Read-only export for landlord backend/payroll (Phase 3) |

All write endpoints must be idempotent (client-generated UUID per batch) to tolerate retry-after-reconnect.

---

## 5. Functional Requirements

### 5.1 Hardware Anchors

**REQ-4.1.1 — BLE Anchor Detection**
- Trigger: `BluetoothLeScanner` background scan matches a corporate-assigned Service UUID registered to the bound `propertyCode`.
- Behavior: On match, post a high-priority heads-up notification ("Scan your item — [Property] recycling room detected") within the latency budget in §6.
- Acceptance: Notification fires when phone enters BLE range of a beacon whose UUID is in the user's bound property's beacon list; does not fire for beacons belonging to other properties.

**REQ-4.1.2 — NFC Tap Launch**
- Trigger: NDEF tag matching the app's registered intent filter is tapped.
- Behavior: App wakes (cold or background) and navigates directly to the camera scanner screen, bypassing dashboard/home.
- Acceptance: Tap-to-scanner-visible end-to-end time meets §6 latency budget; works with screen locked (subject to OS NFC-while-locked settings) and app killed.

### 5.2 ML Scan Pipeline

See §5.5 (full tier breakdown) for pipeline detail; this section covers the resulting rule-display requirements.

**REQ-4.2.1 — On-Device Object Detection**
- The scanner must classify generic material shapes (jug, box, can, carton) using ML Kit Object Detection with zero network calls.

**REQ-4.2.2 — Rule Matching & Display**
| Condition | UI Output |
|---|---|
| Plastic/paper item, detected footprint < 3in × 3in | `❌ Too Small for Pellitteri Systems. Discard in Trash.` |
| Plastic jug / can / milk carton | `⚠️ Madison Rule: Keep 3D! Do not crush or flatten.` |
| Cardboard box | `✅ Madison Rule: Flatten completely before discarding.` |

Acceptance: each condition is a distinct, unit-testable rule-resolution function (`resolveDisplayRule(item: RecyclableItem, dimensions: BoundingBox?): RuleMessage`) independent of UI, with one test case per row above plus one negative case (no rule match → generic "check local guidelines" fallback, never a blank state).

### 5.3 B2B Onboarding

**REQ-4.3.1 / 4.3.2 — Property Code Binding**
- On registration, user enters a property code (format: `{LandlordAbbrev}-{PropertyAbbrev}-{Unit}`, e.g. `MPM-OAK-302`).
- Client validates format locally (regex) before calling `/v1/property/{code}/bind`; server validates existence/active status.
- On success: unlock building-specific messaging, write a `PropertyOrg` row, and append an immutable compliance-log entry (timestamp + user ID + property code) recording that biannual recycling education was delivered. This entry is never deleted by the client.
- Failure modes: invalid format (inline validation error), code not found (server 404 → "check with your property manager" message), code expired/inactive (server 409 → same message, no distinction exposed to tenant to avoid leaking landlord account state).

### 5.4 Gamification / Rewards

**REQ-4.4.1** — Each verified scan (see REQ-4.6.1 for "verified") increments the session's point total by a server-configurable amount (default 5).

**REQ-4.4.2** — Points write to `points_ledger` locally immediately; sync to landlord backend is batched and fires on connectivity regain (WorkManager, `NetworkType.CONNECTED` constraint), encrypted in transit (TLS) — see §7 for at-rest handling.

**REQ-4.4.3** — UI displays a progress bar: `current month points / monthly threshold` (default threshold 100 pts = $10 credit, both server-configurable per property).

### 5.5 ML Pipeline Detail (Six-Tier)

```
Camera Frame → Tier 1 (Barcode) → [hit] → Room lookup → done
                    │ [miss]
                    ▼
              Tier 2 (Object bounding box)
                    │
                    ▼
              Tier 3 (OCR within bounding box) ──[hit]──▶ done
                    │ [no confident match]
                    ▼
              Tier 2.5 (on-device CV material classifier) ──[confident]──▶ done
                    │ [low-confidence]
                    ▼
              Tier 3.5 (cloud vision backup, network-gated) ──[hit]──▶ done
                    │ [no network, or no confident match]
                    ▼
              Tier 4 (Manual 1-tap fallback)
```

| Tier | Dependency | Trigger | Latency Budget | Output |
|---|---|---|---|---|
| 1 | ML Kit Barcode Scanning | Continuous analysis at 30 FPS | Frame → Room result < 150ms | `RecyclableItem` via barcode key |
| 2 | ML Kit Object Detection & Tracking | Tier 1 miss | N/A (feeds Tier 3) | Bounding box isolating item from background |
| 3 | ML Kit Text Recognition (Latin) | Runs inside Tier 2's bounding box | N/A (feeds Tier 2.5 if low-confidence) | Inferred `materialType` via keyword dictionary match (e.g. "Oat Milk" → carton, "Chobani" → yogurt cup) |
| 2.5 | On-device TFLite classifier (MobileNetV2, transfer-learned on TrashNet) | Tier 3 no confident match | N/A (feeds Tier 3.5 if low-confidence) | Inferred `materialType` from image content alone — no text/barcode required. Runs fully offline. |
| 3.5 | Cloud vision backup (vendor TBD) | Tier 2.5 low-confidence, **and** `NetworkMonitor` reports connectivity | N/A (feeds Tier 4 if unavailable/low-confidence) | Same `materialType` inference, intended for cases the offline model can't cover |
| 4 | Compose overlay UI | Tiers 1–3.5 all fail, low-confidence, or offline | N/A (blocking on user input) | User taps one of: 📦 Cardboard / 🥤 Plastic Jug/Bottle / 🥫 Metal Can / 🥛 Drink Carton |

Acceptance:
- Tier 1 must short-circuit Tiers 2–4 entirely when a barcode is decoded (no wasted CV inference cycles).
- Tier 3's keyword dictionary is a versioned local asset (`assets/ocr_keywords_v{n}.json`), not hardcoded in Kotlin, so it can be updated without a full app release via the rules sync channel.
- **Tier 2.5 (on-device classifier)** is the primary CV path — it must never require network. Its output space is `{CARDBOARD, METAL_CAN, OTHER, PLASTIC_FILM, PLASTIC_JUG}`: `DRINK_CARTON` is the only `MaterialType` excluded from v1's training data (no dataset used has a drink-carton/tetra-pak class) rather than trained with none. It remains reachable via Tier 3 (OCR) and, once implemented, Tier 3.5. Training data: TrashNet + the Kaggle `alistairking/recyclable-and-household-waste-classification` dataset (17,045 images combined), with TrashNet's ambiguous single "plastic" folder deliberately dropped in favor of the Kaggle dataset's separately-labeled bottle/jug vs. bag/film subcategories. Validation accuracy (two-phase training — frozen-backbone head training, then fine-tuning the backbone's top layers at a low learning rate): **93.1% overall** (`CARDBOARD` 88.5% precision/96.4% recall, `METAL_CAN` 90.9%/91.7%, `OTHER` 96.0%/92.9%, `PLASTIC_FILM` 86.3%/97.5%, `PLASTIC_JUG` 88.6%/91.0%). Retraining instructions: `tools/material_classifier/README.md`.
- **Tier 3.5 (cloud vision backup)** is optional and network-gated — `CloudVisionMaterialTierImpl` is currently a deliberate stub (always declines) pending a vendor/API-key decision; it must never block or degrade the offline path. This is a scoped, documented exception to the "No cloud vision fallback" architecture note in §2: images only ever leave the device through this tier, only when online, and only after the fully-offline tiers (1–2.5) have already missed.
- Tier 4 selection applies the same `resolveDisplayRule` function as REQ-4.2.2 and awards points identically to a Tier 1–3.5 resolution (no point penalty for manual fallback).

### 5.6 Verified-Compliance Gate (Anti-Fraud + Legal Log)

**REQ-4.6.1 — Mandatory Photo Attestation**
- Points remain in a `PENDING` (unawarded, greyed-out) state until the user submits a second photo showing the item in its required end-state (flattened box / uncrushed jug, matched against the rule from REQ-4.2.2).
- This photo must come from the live camera surface (see §7.5) — no gallery import.
- Acceptance: `pointsAwarded` field only transitions from 0 to the awarded value after `attestationPhotoUri` is non-null and passes the anti-spoofing capture constraint.

**REQ-4.6.2 — Chronological Peer Auditing**
- On app open, a tenant may see a 1-tap "report contamination" prompt referencing the building's shared bin.
- A flagged event cross-references `scan_log` ordered by `timestamp` for that `propertyCode` to identify the most recent contributor(s) prior to the flag.
- This is a soft signal to the landlord dashboard (Phase 3), not an automated penalty — no automatic point deduction or user-facing accusation in v1 (legal/liability risk of false accusation must be scoped with the user before building punitive logic).

---

## 6. Non-Functional Requirements (Measurable)

| ID | Requirement | Threshold | Verification Method |
|---|---|---|---|
| NFR-1 | Offline core function | Scanner UI, Room lookup, hardware trigger logic operate with airplane mode on | Manual QA in airplane mode; CI test with network layer mocked to always-fail |
| NFR-2 | NFC-to-instruction latency | < 800ms from tag tap to rule text rendered | Instrumented UI test with `SystemClock.elapsedRealtime()` timestamps at tap-receive and compose-render |
| NFR-3 | Barcode-to-result latency | < 150ms frame capture → Room query result | Benchmark test (`androidx.benchmark`) over 100 sample frames |
| NFR-4 | BLE background battery | < 1% of daily battery budget | `BatteryStats`/Battery Historian profiling over 24h soak test with scan duty-cycled (not continuous) |
| NFR-5 | Location privacy | No GPS/lat-long ever persisted or transmitted | Static analysis: no `FusedLocationProviderClient`/`LocationManager` API usage in codebase (lint rule); data model has no lat/long fields |
| NFR-6 | Rate limiting | Max 3 unique product logging events/user/day count toward points | Server-side enforcement (client-side check is UX-only, not trusted) — see REQ-5.5 below |

### 5.5 (NFR detail) Fraud Prevention & Rate Limiting

- Scans must occur within a verified BLE beacon boundary (RSSI/proximity threshold TBD — see Open Questions) or via the Tier-4/photo fallback (§5.6 hardware fail-safe below) to count toward points.
- Server enforces max 3 point-eligible scans per `userId` per calendar day (property-local timezone). Client mirrors this check for immediate UX feedback but the server is the source of truth — a client-only limit is trivially bypassed by a modified APK.

### 5.6 (Hardware Fail-Safe)

- If no BLE beacon is reachable (dead battery) and no NFC tag is available, the app permits a manual, time-stamped photo upload path that awards points provisionally and flags the landlord backend that the anchor at that property may be offline (server-side alert if a property receives N consecutive fallback submissions — threshold TBD).

### 5.7 Anti-Spoofing Camera Constraints

- All attestation and disposal-scan photos must originate from a live `Camera2`/CameraX surface capture within the app's own scanner Activity/Composable.
- Programmatically block: gallery `ACTION_PICK`/`ACTION_GET_CONTENT` ingestion into the scan/attestation flow, virtual/streamed camera sources (detect via `Camera2` characteristics where feasible), and any Intent-based image hand-off from third-party apps into the attestation field.
- Acceptance: attestation photo capture path has no code branch that accepts a `Uri` not produced by the app's own `MediaStore` entry created immediately before capture.

---

## 7. Security & Privacy

- **7.1** No GPS/location coordinates stored or transmitted, ever (see NFR-5).
- **7.2** The system records only: `userId` performed a scan while in range of `anchorId` (property-scoped beacon/NFC identifier), never raw coordinates or trilaterated position.
- **7.3** All network traffic over TLS 1.2+.
- **7.4** `scan_log` and `points_ledger` tables (contain `userId` + behavioral history) encrypted at rest (SQLCipher or Room's `EncryptedFile`/Jetpack Security equivalent). `madison_recyclables` (public rule data) does not require encryption.
- **7.5** See §5.7 anti-spoofing constraints.
- **7.6** Property codes and landlord JWTs are scoped — a tenant JWT can never read another property's compliance log or another tenant's scan history (server-side authorization check on every endpoint in §4.2, not just client-side hiding).

---

## 8. Phasing (Engineering Milestones)

| Phase | Weeks | Deliverables | Exit Criteria |
|---|---|---|---|
| 1 — Local Core Engine | 1–4 | Room schema (§3.1) implemented; Compose camera view; Tiers 1–4 ML pipeline wired end-to-end against a seeded local rule set | A device in airplane mode can scan a real barcode, jug, and box and get correct REQ-4.2.2 output for all three |
| 2 — Hardware Prototyping | 5–6 | BLE background scanner + notification; NFC intent filter + direct-to-scanner launch | NFR-2 (<800ms) met on at least 2 physical test devices; BLE detection verified in a real basement/interior space |
| 3 — B2B Enterprise Layer | 7–8 | Property code bind flow; points/compliance-log sync endpoints (Ktor/Firebase); minimal landlord-facing export | End-to-end: tenant binds code → scans → points sync → visible in backend export within one sync cycle |

---

## 9. Success Metrics (unchanged from PRD, restated as testable targets)

| Metric | Target | Measurement |
|---|---|---|
| WAU | >75% of a building's registered tenants open the app ≥2x/week | Analytics event count / registered-tenant count per property, weekly |
| Scan abandonment | <5% of camera sessions closed before item log completes | `scan_started` vs `scan_completed` event ratio |
| Contamination fine reduction | Measurable drop in hauler contamination fines for pilot property within 90 days | External: property manager-reported invoice comparison, pre/post rollout |
| Landlord renewal | Pilot properties renew after 6-month trial | Contract/billing system |

---

## 10. Open Questions (must be resolved before Phase 2/3 implementation)

1. **BLE proximity threshold**: What RSSI/distance counts as "within a verified beacon boundary" for REQ-5.5 (fraud prevention)? Needs a concrete dBm or estimated-distance cutoff, not just "in range."
2. **Peer auditing (REQ-4.6.2) liability**: Confirm no automated penalty/accusation ships in v1 — legal review needed if this evolves beyond a soft landlord-dashboard signal.
3. **Consecutive fallback threshold** (§5.6): What count of manual-fallback submissions at one property triggers a "beacon may be dead" alert to the landlord?
4. **Points/credit configurability**: Confirm points-per-scan and monthly threshold are per-property server config (as specified in §5.4) vs. global constants — affects backend schema in Phase 3.
5. **Backend hosting choice**: Firebase vs. self-hosted Ktor server — affects §4.2 contract implementation but not the contract shape itself; PRD leaves this open.
