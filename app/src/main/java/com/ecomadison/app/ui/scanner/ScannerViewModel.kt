package com.ecomadison.app.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
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
    private val logScanUseCase: LogScanUseCase,
    @param:ApplicationContext private val context: Context
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
            ScannerIntent.CorrectionRequested -> _uiState.value = ScannerUiState(
                isAwaitingManualSelection = true,
                lastBoundingBox = _uiState.value.lastBoundingBox
            )
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
                    is ScanTierResult.MaterialResolved -> {
                        saveDebugFrame(bitmap, "material_${result.materialType}")
                        handleMaterialResolved(result)
                    }
                    is ScanTierResult.ManualFallbackRequired -> {
                        saveDebugFrame(bitmap, "manual_fallback")
                        _uiState.value = ScannerUiState(
                            isAwaitingManualSelection = true,
                            lastBoundingBox = result.boundingBox
                        )
                    }
                    // Object-presence streak just crossed its threshold -- the *next* frame does
                    // the real (possibly slow, network-bound) resolution work. Show the spinner now.
                    ScanTierResult.Analyzing -> _uiState.value = ScannerUiState(isAnalyzing = true)
                    // Nothing detected yet, or an object hasn't stayed in frame long enough to
                    // trust it — leave the UI as-is and keep scanning. Exception: if we were
                    // mid-Analyzing and the object left frame before resolution ran, the
                    // coordinator gave up on that attempt internally, so clear the spinner too --
                    // otherwise it would be stuck on screen with nothing left driving it forward.
                    ScanTierResult.Pending -> if (_uiState.value.isAnalyzing) {
                        _uiState.value = ScannerUiState()
                    }
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
        _uiState.value = ScannerUiState(ruleMessage = rule, ruleDetail = item.rulesText.takeIf { it.isNotBlank() }, resolvedFromTier = 1)
        logScan(barcode = barcode, materialType = item.materialType, tier = 1)
    }

    private suspend fun handleMaterialResolved(result: ScanTierResult.MaterialResolved) {
        val item = getFallbackRuleUseCase(result.materialType).first() ?: run {
            _uiState.value = ScannerUiState(ruleMessage = RuleMessage.GenericFallback, resolvedFromTier = 3)
            logScan(barcode = null, materialType = result.materialType, tier = 3, pointsAwarded = 0)
            return
        }
        val rule = resolveDisplayRuleUseCase(item, result.boundingBox)
        _uiState.value = ScannerUiState(
            ruleMessage = rule,
            ruleDetail = item.rulesText.takeIf { it.isNotBlank() },
            productLabel = result.productCategory?.displayName,
            lastBoundingBox = result.boundingBox,
            resolvedFromTier = 3
        )
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
            _uiState.value = ScannerUiState(ruleMessage = rule, ruleDetail = item.rulesText.takeIf { it.isNotBlank() }, resolvedFromTier = 4)
            logScan(barcode = null, materialType = item.materialType, tier = 4)
        }
    }

    /**
     * Temporary Phase 1 diagnostic tool: saves the exact bitmap the classifier/OCR tiers saw for
     * every resolved (non-Pending) frame, so a wrong result on-device can be root-caused against
     * the real live-camera frame instead of a separately-taken photo that may differ in
     * resolution/exposure/framing from what ImageAnalysis actually captured. Writes to
     * app-specific external storage (no permissions needed); pull with:
     *   adb pull /sdcard/Android/data/com.ecomadison.app/files/scan_debug
     * Flip [DEBUG_SAVE_SCAN_FRAMES] off (or delete this) once the pipeline is trusted.
     */
    private fun saveDebugFrame(bitmap: Bitmap, label: String) {
        if (!DEBUG_SAVE_SCAN_FRAMES) return
        try {
            val dir = context.getExternalFilesDir(DEBUG_FRAME_SUBDIR) ?: return
            dir.mkdirs()
            dir.resolve("${System.currentTimeMillis()}_$label.jpg").outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: IOException) {
            Log.w("ScannerViewModel", "Failed to save debug frame", e)
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

        const val DEBUG_SAVE_SCAN_FRAMES = true
        const val DEBUG_FRAME_SUBDIR = "scan_debug"
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
