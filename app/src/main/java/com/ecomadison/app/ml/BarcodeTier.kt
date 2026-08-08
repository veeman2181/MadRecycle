package com.ecomadison.app.ml

import com.google.mlkit.vision.common.InputImage

/** Tier 1 (§5.5): continuous barcode analysis, budgeted at frame -> Room result < 150ms (NFR-3). */
interface BarcodeTier {
    suspend fun scan(image: InputImage): String?
}
