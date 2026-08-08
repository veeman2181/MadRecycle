package com.ecomadison.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ecomadison.app.domain.model.SyncStatus

/** Spec §3.1. Populated by the Phase 3/§5.4 gamification flow; unused by the Phase 1 read path. */
@Entity(
    tableName = "points_ledger",
    indices = [Index(value = ["userId", "monthYear"])]
)
data class PointsLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val propertyCode: String,
    val points: Int,
    val monthYear: String,
    val syncStatus: SyncStatus
)
