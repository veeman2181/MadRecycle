package com.ecomadison.app.domain.model

/**
 * One resolved scan event. Phase 1 writes this for every completed tier resolution; `propertyCode`,
 * `anchorId`, `attestationPhotoUri` and `pointsAwarded` stay unset until the hardware-anchor and
 * gamification layers (Phase 2/3, out of scope here) are wired in.
 */
data class ScanLogEntry(
    val id: Long = 0,
    val userId: String,
    val propertyCode: String?,
    val barcode: String?,
    val materialType: MaterialType,
    val resolvedByTier: Int,
    val anchorId: String?,
    val attestationPhotoUri: String?,
    val pointsAwarded: Int,
    val timestamp: Long,
    val syncStatus: SyncStatus
)
