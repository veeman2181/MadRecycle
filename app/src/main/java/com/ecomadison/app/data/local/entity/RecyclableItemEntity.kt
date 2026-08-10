package com.ecomadison.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ecomadison.app.domain.model.MaterialType

/**
 * Spec §3.1. `barcode` is the UPC-A/E or EAN key; a value of "" identifies a Tier 2-4 fallback
 * row keyed by [materialType] instead (see ResolveDisplayRuleUseCase / RulesRepositoryImpl).
 *
 * `barcode` is NOT the primary key: multiple fallback rows legitimately share `barcode = ""`
 * (one per [MaterialType]), and a single-column primary key on `barcode` would let
 * `OnConflictStrategy.REPLACE` silently overwrite every earlier ""-barcode row with the last one
 * inserted, leaving only one fallback material resolvable at a time. `id` is a surrogate key
 * purely for Room; lookups still go through the indexed `barcode` column via plain queries.
 */
@Entity(tableName = "madison_recyclables", indices = [Index("barcode")])
data class RecyclableItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val itemName: String,
    val materialType: MaterialType,
    val rulesText: String,
    val minDimensionInches: Float?,
    val requiresFlatten: Boolean,
    val requires3D: Boolean,
    val isRecyclableAsIs: Boolean,
    val lastUpdatedTimestamp: Long
)
