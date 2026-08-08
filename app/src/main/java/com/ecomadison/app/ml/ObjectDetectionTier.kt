package com.ecomadison.app.ml

import com.ecomadison.app.domain.model.BoundingBox
import com.google.mlkit.vision.common.InputImage

/** Tier 2 (§5.5): runs only on a Tier 1 miss; isolates the item's bounding box for Tier 3. */
interface ObjectDetectionTier {
    suspend fun detect(image: InputImage): BoundingBox?
}
