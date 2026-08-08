package com.ecomadison.app.ml

import com.ecomadison.app.domain.model.MaterialType
import com.google.mlkit.vision.common.InputImage

/** Tier 3 (§5.5): OCR inside the Tier 2 bounding box, resolved via the keyword dictionary. */
interface OcrTier {
    suspend fun recognizeMaterial(image: InputImage): MaterialType?
}
