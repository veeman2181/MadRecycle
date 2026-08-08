package com.ecomadison.app.ml

import android.graphics.Rect
import com.ecomadison.app.domain.model.BoundingBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObjectDetectionTierImpl @Inject constructor() : ObjectDetectionTier {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableClassification()
            .build()
    )

    override suspend fun detect(image: InputImage): BoundingBox? {
        val largest = detector.process(image).await()
            .maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height().toLong() }
            ?: return null
        return largest.boundingBox.toBoundingBoxInches()
    }

    /**
     * NFR/Open-Questions note: converting a single 2D frame's pixel bounds to physical inches
     * without depth data or a reference object is inherently approximate. This fixed
     * pixels-per-inch assumption is a Phase 1 placeholder calibrated for a typical
     * arm's-length scan distance; a production build should replace it with a proper
     * reference-object or camera-intrinsics calibration pass.
     */
    private fun Rect.toBoundingBoxInches(): BoundingBox = BoundingBox(
        widthInches = width() / ASSUMED_PIXELS_PER_INCH,
        heightInches = height() / ASSUMED_PIXELS_PER_INCH
    )

    private companion object {
        const val ASSUMED_PIXELS_PER_INCH = 80f
    }
}
