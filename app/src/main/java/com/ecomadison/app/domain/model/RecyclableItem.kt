package com.ecomadison.app.domain.model

/**
 * Domain-level disposal rule for one item, resolved by barcode key (Tier 1) or synthesized
 * from a [MaterialType] guess for Tiers 2-4 (see ResolveDisplayRuleUseCase / ScanPipelineCoordinator).
 */
data class RecyclableItem(
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
