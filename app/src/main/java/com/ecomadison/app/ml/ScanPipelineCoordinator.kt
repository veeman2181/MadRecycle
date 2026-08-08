package com.ecomadison.app.ml

import android.graphics.Bitmap
import com.ecomadison.app.network.NetworkMonitor
import com.google.mlkit.vision.common.InputImage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §5.5 tier orchestration. Kept independent of CameraX/ViewModel so the short-circuit contract
 * ("Tier 1 must short-circuit Tiers 2-4 entirely when a barcode is decoded") is a plain unit test
 * against fakes of [BarcodeTier]/[ObjectDetectionTier]/[OcrTier]/[MaterialClassifierTier]/
 * [CloudVisionMaterialTier].
 *
 * Resolution order after a Tier 1 miss: OCR wins when it hits (brand text is more precise than
 * a coarse CV guess); otherwise the on-device classifier (Tier 2.5); otherwise the cloud backup
 * (Tier 3.5) if-and-only-if [NetworkMonitor] reports connectivity; otherwise Tier 4 manual.
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
    suspend fun analyze(image: InputImage, bitmap: Bitmap): ScanTierResult {
        barcodeTier.scan(image)?.let { barcode ->
            return ScanTierResult.BarcodeResolved(barcode)
        }

        val boundingBox = objectDetectionTier.detect(image)

        ocrTier.recognizeMaterial(image)?.let { material ->
            return ScanTierResult.MaterialResolved(material, boundingBox)
        }

        materialClassifierTier.classify(bitmap)?.let { classification ->
            return ScanTierResult.MaterialResolved(classification.materialType, boundingBox)
        }

        if (networkMonitor.isConnected()) {
            cloudVisionMaterialTier.classify(bitmap)?.let { classification ->
                return ScanTierResult.MaterialResolved(classification.materialType, boundingBox)
            }
        }

        return ScanTierResult.ManualFallbackRequired(boundingBox)
    }
}
