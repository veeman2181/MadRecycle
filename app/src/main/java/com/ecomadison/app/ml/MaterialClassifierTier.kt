package com.ecomadison.app.ml

import android.graphics.Bitmap

/**
 * On-device CV material classifier (§5.5 Tier 2.5): a MobileNetV2 transfer-learning model
 * fine-tuned on a Kaggle recyclables dataset at product-level granularity (see
 * tools/material_classifier/train_granular_experiment.py), bundled as a TFLite asset. Runs
 * entirely offline. Every [com.ecomadison.app.domain.model.MaterialType] except DRINK_CARTON is
 * reachable — no training data exists for drink cartons, so a confident guess is never returned
 * for that one; OCR (Tier 3) and the cloud backup (Tier 3.5) are the paths that can still resolve
 * it. A [MaterialClassification.productCategory] guess is only populated for the subset of
 * labels fine-grained enough to map to a [com.ecomadison.app.domain.model.ProductCategory].
 *
 * Takes a decoded [Bitmap] rather than ML Kit's [com.google.mlkit.vision.common.InputImage]:
 * InputImage doesn't publicly expose pixel data for camera-frame-backed instances, and TFLite's
 * TensorImage works directly from a Bitmap.
 */
interface MaterialClassifierTier {
    suspend fun classify(bitmap: Bitmap): MaterialClassification?
}
