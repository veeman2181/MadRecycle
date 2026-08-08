package com.ecomadison.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ecomadison.app.domain.model.MaterialType

/**
 * Spec §3.1. `barcode` is the UPC-A/E or EAN key; a value of "" identifies a Tier 2-4 fallback
 * row keyed by [materialType] instead (see ResolveDisplayRuleUseCase / RulesRepositoryImpl).
 */
@Entity(tableName = "madison_recyclables")
data class RecyclableItemEntity(
    @PrimaryKey val barcode: String,
    val itemName: String,
    val materialType: MaterialType,
    val rulesText: String,
    val minDimensionInches: Float?,
    val requiresFlatten: Boolean,
    val requires3D: Boolean,
    val lastUpdatedTimestamp: Long
)
