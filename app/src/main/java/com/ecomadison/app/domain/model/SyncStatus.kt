package com.ecomadison.app.domain.model

/** Upload state of a locally-written ledger/log row awaiting the (Phase 3) backend sync channel. */
enum class SyncStatus {
    PENDING,
    SYNCED,
    FAILED
}
