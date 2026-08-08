package com.ecomadison.app.ui.scanner

import androidx.camera.core.ImageProxy
import com.ecomadison.app.domain.model.BoundingBox
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RuleMessage

/** MVI state for the single Phase 1 screen: live camera feed + resolved disposal rule. */
data class ScannerUiState(
    val ruleMessage: RuleMessage? = null,
    val isAwaitingManualSelection: Boolean = false,
    val lastBoundingBox: BoundingBox? = null,
    val resolvedFromTier: Int? = null
)

sealed interface ScannerIntent {
    /** Owns [imageProxy]'s lifecycle; the ViewModel closes it once tier resolution completes. */
    data class FrameCaptured(val imageProxy: ImageProxy) : ScannerIntent
    data class ManualMaterialSelected(val materialType: MaterialType) : ScannerIntent
    data object RuleDismissed : ScannerIntent
}
