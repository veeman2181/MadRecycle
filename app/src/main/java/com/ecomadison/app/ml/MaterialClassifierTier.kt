package com.ecomadison.app.ml

import android.graphics.Bitmap

/**
 * On-device CV material classifier (§5.5 Tier 2.5): a MobileNetV2 transfer-learning model
 * fine-tuned on TrashNet + a Kaggle recyclables dataset, bundled as a TFLite asset. Runs
 * entirely offline. Its output space is {CARDBOARD, METAL_CAN, OTHER, PLASTIC_FILM,
 * PLASTIC_JUG} — it does not include DRINK_CARTON, since no training data exists for it, so a
 * confident guess is never returned for that one; OCR (Tier 3) and the cloud backup (Tier 3.5)
 * are the paths that can still resolve it.
 *
 * Takes a decoded [Bitmap] rather than ML Kit's [com.google.mlkit.vision.common.InputImage]:
 * InputImage doesn't publicly expose pixel data for camera-frame-backed instances, and TFLite's
 * TensorImage works directly from a Bitmap.
 */
interface MaterialClassifierTier {
    suspend fun classify(bitmap: Bitmap): MaterialClassification?
}
