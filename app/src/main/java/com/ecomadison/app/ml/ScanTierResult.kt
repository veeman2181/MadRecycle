package com.ecomadison.app.ml

import com.ecomadison.app.domain.model.BoundingBox
import com.ecomadison.app.domain.model.MaterialType

/** Outcome of running one camera frame through the §5.5 four-tier pipeline. */
sealed interface ScanTierResult {

    /** Tier 1 hit. Short-circuits Tiers 2-4 entirely. */
    data class BarcodeResolved(val barcode: String) : ScanTierResult

    /** Tier 3 confidently inferred a material from OCR text within the Tier 2 bounding box. */
    data class MaterialResolved(val materialType: MaterialType, val boundingBox: BoundingBox?) : ScanTierResult

    /** Tiers 1-3 all missed or were low-confidence; UI must show the Tier 4 manual overlay. */
    data class ManualFallbackRequired(val boundingBox: BoundingBox?) : ScanTierResult
}
