package com.ecomadison.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.SyncStatus

/**
 * Spec §3.1. `propertyCode` is nullable here (spec models it non-null): Phase 1 has no
 * property-binding flow yet, so scans are logged unbound until Phase 3 wires that in.
 */
@Entity(
    tableName = "scan_log",
    indices = [
        Index(value = ["userId", "timestamp"]),
        Index(value = ["syncStatus"])
    ]
)
data class ScanLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
