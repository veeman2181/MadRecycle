package com.ecomadison.app.ml

import android.graphics.Bitmap

/**
 * §5.5 Tier 3.5: optional online backup, only ever attempted by [ScanPipelineCoordinator] when
 * NetworkMonitor reports connectivity. Never blocks or gates the offline path — a `null` result
 * (no connectivity, or the vendor call itself declines) simply falls through to Tier 4 manual.
 */
interface CloudVisionMaterialTier {
    suspend fun classify(bitmap: Bitmap): MaterialClassification?
}
