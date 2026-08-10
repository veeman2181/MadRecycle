package com.ecomadison.app.ml

import android.graphics.Bitmap
import com.ecomadison.app.domain.model.BoundingBox
import com.ecomadison.app.network.NetworkMonitor
import com.google.mlkit.vision.common.InputImage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * §5.5 tier orchestration. Kept independent of CameraX/ViewModel so the short-circuit contract
 * ("Tier 1 must short-circuit Tiers 2-4 entirely when a barcode is decoded") is a plain unit test
 * against fakes of [BarcodeTier]/[ObjectDetectionTier]/[OcrTier]/[MaterialClassifierTier]/
 * [CloudVisionMaterialTier].
 *
 * Resolution order after a Tier 1 miss: OCR wins when it hits (brand text is more precise than
 * a coarse CV guess, and it's free/local); otherwise the cloud vision tier (Tier 3.5) -- now the
 * primary CV resolver, since it can actually reason about an object instead of forcing it into a
 * fixed label set (see tools/material_classifier/README.md's granular-experiment write-up for why
 * the on-device model alone isn't enough); otherwise the on-device classifier (Tier 2.5), demoted
 * to a connectivity/latency fallback for when the cloud call is unavailable, errors, or times out.
 *
 * Tiers 2-3.5 only run once [ObjectDetectionTier] finds something in frame, and that presence must
 * repeat across [CONFIRMATION_FRAME_COUNT] consecutive frames before a (comparatively slow, costly)
 * cloud request is even attempted -- otherwise a single frame during camera raise/shake would burn
 * a network call on nothing. Once that gate is crossed, [analyze] returns [ScanTierResult.Analyzing]
 * for exactly one frame so the UI can show a spinner *before* the slow call starts, then performs
 * the actual resolution (including the network round-trip) on the next call.
 */
@Singleton
class ScanPipelineCoordinator @Inject constructor(
    private val barcodeTier: BarcodeTier,
    private val objectDetectionTier: ObjectDetectionTier,
    private val ocrTier: OcrTier,
    private val materialClassifierTier: MaterialClassifierTier,
    private val cloudVisionMaterialTier: CloudVisionMaterialTier,
    private val networkMonitor: NetworkMonitor
) {
    // Safe as plain mutable state: ScannerViewModel's frameMutex guarantees analyze() calls are
    // never concurrent.
    private var objectStreak = 0
    private var awaitingResolution = false

    suspend fun analyze(image: InputImage, bitmap: Bitmap): ScanTierResult {
        barcodeTier.scan(image)?.let { barcode ->
            reset()
            return ScanTierResult.BarcodeResolved(barcode)
        }

        val boundingBox = objectDetectionTier.detect(image)
        if (boundingBox == null) {
            // Nothing object-shaped in frame — don't burn a CV/OCR/network pass on empty
            // background, and don't let it count toward confirming (or breaking) a streak.
            reset()
            return ScanTierResult.Pending
        }

        if (awaitingResolution) {
            reset()
            return resolve(image, bitmap, boundingBox)
        }

        objectStreak++
        if (objectStreak < CONFIRMATION_FRAME_COUNT) {
            return ScanTierResult.Pending
        }
        // Streak crossed the gate on this frame -- signal "about to do real work" without
        // actually spending the network call yet, so the UI's spinner appears before the wait
        // rather than the wait happening silently inside this same frame's processing.
        objectStreak = 0
        awaitingResolution = true
        return ScanTierResult.Analyzing
    }

    private suspend fun resolve(image: InputImage, bitmap: Bitmap, boundingBox: BoundingBox): ScanTierResult {
        val candidate = ocrTier.recognizeMaterial(image)?.let { MaterialClassification(it, confidence = 1f) }
            ?: cloudVisionCandidate(bitmap)
            ?: materialClassifierTier.classify(bitmap)

        return if (candidate != null) {
            ScanTierResult.MaterialResolved(candidate.materialType, boundingBox, candidate.productCategory)
        } else {
            ScanTierResult.ManualFallbackRequired(boundingBox)
        }
    }

    /**
     * Null here (no connectivity, no proxy configured, request error, or timeout) is exactly the
     * "fall through to the on-device classifier" signal -- the backend always returns a concrete
     * classification (defaulting to OTHER) when it does respond, so there's no ambiguity between
     * "cloud said unsure" and "cloud didn't answer."
     */
    private suspend fun cloudVisionCandidate(bitmap: Bitmap): MaterialClassification? {
        if (!networkMonitor.isConnected()) return null
        return withTimeoutOrNull(CLOUD_VISION_TIMEOUT_MS) { cloudVisionMaterialTier.classify(bitmap) }
    }

    private fun reset() {
        objectStreak = 0
        awaitingResolution = false
    }

    private companion object {
        const val CONFIRMATION_FRAME_COUNT = 3
        const val CLOUD_VISION_TIMEOUT_MS = 4_000L
    }
}
