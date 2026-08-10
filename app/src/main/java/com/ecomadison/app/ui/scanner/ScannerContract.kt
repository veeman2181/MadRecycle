package com.ecomadison.app.ui.scanner

import androidx.camera.core.ImageProxy
import com.ecomadison.app.domain.model.BoundingBox
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RuleMessage

/** MVI state for the single Phase 1 screen: live camera feed + resolved disposal rule. */
data class ScannerUiState(
    val ruleMessage: RuleMessage? = null,
    /** Item-specific detail sourced from RecyclableItem.rulesText, shown under the headline rule. */
    val ruleDetail: String? = null,
    /** Display-only product guess (e.g. "plastic detergent bottle"), null when unavailable -- never affects ruleMessage. */
    val productLabel: String? = null,
    /** True while ScanTierResult.Analyzing is in effect -- cloud (or fallback) resolution is in flight. */
    val isAnalyzing: Boolean = false,
    val isAwaitingManualSelection: Boolean = false,
    val lastBoundingBox: BoundingBox? = null,
    val resolvedFromTier: Int? = null
)

sealed interface ScannerIntent {
    /** Owns [imageProxy]'s lifecycle; the ViewModel closes it once tier resolution completes. */
    data class FrameCaptured(val imageProxy: ImageProxy) : ScannerIntent
    data class ManualMaterialSelected(val materialType: MaterialType) : ScannerIntent
    data object RuleDismissed : ScannerIntent
    /** User tapped "Not right?" on a [ScannerUiState.productLabel] guess -- reopens the Tier 4 manual picker. */
    data object CorrectionRequested : ScannerIntent
}
