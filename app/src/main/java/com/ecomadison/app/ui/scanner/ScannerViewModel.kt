package com.ecomadison.app.ui.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RuleMessage
import com.ecomadison.app.domain.model.ScanLogEntry
import com.ecomadison.app.domain.model.SyncStatus
import com.ecomadison.app.domain.usecase.GetDisposalRuleUseCase
import com.ecomadison.app.domain.usecase.GetFallbackRuleUseCase
import com.ecomadison.app.domain.usecase.LogScanUseCase
import com.ecomadison.app.domain.usecase.ResolveDisplayRuleUseCase
import com.ecomadison.app.ml.ScanPipelineCoordinator
import com.ecomadison.app.ml.ScanTierResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

/**
 * MVI orchestration for the §5.5 four-tier pipeline. One `UiState`, one `Intent` sealed class,
 * unidirectional flow via [StateFlow] per the architecture in §2.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val scanPipelineCoordinator: ScanPipelineCoordinator,
    private val getDisposalRuleUseCase: GetDisposalRuleUseCase,
    private val getFallbackRuleUseCase: GetFallbackRuleUseCase,
    private val resolveDisplayRuleUseCase: ResolveDisplayRuleUseCase,
    private val logScanUseCase: LogScanUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    /** Camera frames arrive faster than a Room round-trip completes; drop overlapping frames rather than queue them. */
    private val frameMutex = Mutex()

    fun onIntent(intent: ScannerIntent) {
        when (intent) {
            is ScannerIntent.FrameCaptured -> onFrameCaptured(intent)
            is ScannerIntent.ManualMaterialSelected -> onManualMaterialSelected(intent.materialType)
            ScannerIntent.RuleDismissed -> _uiState.value = ScannerUiState()
        }
    }

    private fun onFrameCaptured(intent: ScannerIntent.FrameCaptured) {
        val imageProxy = intent.imageProxy
        // A rule is already on screen (or the Tier 4 overlay is up) — hold it until RuleDismissed
        // rather than letting the next frame silently replace it.
        if (_uiState.value.ruleMessage != null || _uiState.value.isAwaitingManualSelection) {
            imageProxy.close()
            return
        }
        if (!frameMutex.tryLock()) {
            imageProxy.close()
            return
        }
        viewModelScope.launch {
            try {
                val mediaImage = imageProxy.image ?: return@launch
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                // ML Kit's detectors take rotation as metadata; the on-device classifier (Tier
                // 2.5) needs an actually-upright bitmap since it has no rotation-aware API.
                val bitmap = imageProxy.toBitmap().rotated(imageProxy.imageInfo.rotationDegrees)
                when (val result = scanPipelineCoordinator.analyze(inputImage, bitmap)) {
                    is ScanTierResult.BarcodeResolved -> handleBarcodeResolved(result.barcode)
                    is ScanTierResult.MaterialResolved -> handleMaterialResolved(result)
                    is ScanTierResult.ManualFallbackRequired -> _uiState.value = ScannerUiState(
                        isAwaitingManualSelection = true,
                        lastBoundingBox = result.boundingBox
                    )
                }
            } finally {
                imageProxy.close()
                frameMutex.unlock()
            }
        }
    }

    private suspend fun handleBarcodeResolved(barcode: String) {
        val item = getDisposalRuleUseCase(barcode).first()
        if (item == null) {
            // REQ-5.5: a decoded barcode short-circuits Tiers 2-4 even when unknown to Room —
            // no wasted CV cycles; show the negative-case fallback directly (§4.2.2 acceptance).
            _uiState.value = ScannerUiState(ruleMessage = RuleMessage.GenericFallback, resolvedFromTier = 1)
            logScan(barcode = barcode, materialType = MaterialType.OTHER, tier = 1, pointsAwarded = 0)
            return
        }
        val rule = resolveDisplayRuleUseCase(item, dimensions = null)
        _uiState.value = ScannerUiState(ruleMessage = rule, resolvedFromTier = 1)
        logScan(barcode = barcode, materialType = item.materialType, tier = 1)
    }

    private suspend fun handleMaterialResolved(result: ScanTierResult.MaterialResolved) {
        val item = getFallbackRuleUseCase(result.materialType).first() ?: run {
            _uiState.value = ScannerUiState(ruleMessage = RuleMessage.GenericFallback, resolvedFromTier = 3)
            logScan(barcode = null, materialType = result.materialType, tier = 3, pointsAwarded = 0)
            return
        }
        val rule = resolveDisplayRuleUseCase(item, result.boundingBox)
        _uiState.value = ScannerUiState(ruleMessage = rule, resolvedFromTier = 3)
        logScan(barcode = null, materialType = item.materialType, tier = 3)
    }

    private fun onManualMaterialSelected(materialType: MaterialType) {
        viewModelScope.launch {
            val boundingBox = _uiState.value.lastBoundingBox
            val item = getFallbackRuleUseCase(materialType).first() ?: run {
                _uiState.value = ScannerUiState(ruleMessage = RuleMessage.GenericFallback, resolvedFromTier = 4)
                logScan(barcode = null, materialType = materialType, tier = 4, pointsAwarded = 0)
                return@launch
            }
            // REQ-5.5 Tier 4 acceptance: same resolution function, no point penalty for manual fallback.
            val rule = resolveDisplayRuleUseCase(item, boundingBox)
            _uiState.value = ScannerUiState(ruleMessage = rule, resolvedFromTier = 4)
            logScan(barcode = null, materialType = item.materialType, tier = 4)
        }
    }

    private suspend fun logScan(barcode: String?, materialType: MaterialType, tier: Int, pointsAwarded: Int = 0) {
        logScanUseCase(
            ScanLogEntry(
                userId = PLACEHOLDER_USER_ID,
                propertyCode = null,
                barcode = barcode,
                materialType = materialType,
                resolvedByTier = tier,
                anchorId = null,
                attestationPhotoUri = null,
                pointsAwarded = pointsAwarded,
                timestamp = System.currentTimeMillis(),
                syncStatus = SyncStatus.PENDING
            )
        )
    }

    private companion object {
        // Phase 3 introduces real auth/property binding; Phase 1 scans are logged under a
        // single local placeholder identity.
        const val PLACEHOLDER_USER_ID = "local-device-user"
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
