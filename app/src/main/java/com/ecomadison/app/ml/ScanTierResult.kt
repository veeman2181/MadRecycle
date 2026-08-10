package com.ecomadison.app.ml

import com.ecomadison.app.domain.model.BoundingBox
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.ProductCategory

/** Outcome of running one camera frame through the §5.5 four-tier pipeline. */
sealed interface ScanTierResult {

    /** Tier 1 hit. Short-circuits Tiers 2-4 entirely. */
    data class BarcodeResolved(val barcode: String) : ScanTierResult

    /**
     * Tiers 2.5-3.5 confidently inferred a material within the Tier 2 bounding box.
     * [productCategory] is an optional finer-grained, display-only guess (null when OCR resolved
     * it, or when the classifier's confidence cleared the material bar but not the stricter
     * product one) -- disposal rules are always resolved from [materialType] alone.
     */
    data class MaterialResolved(
        val materialType: MaterialType,
        val boundingBox: BoundingBox?,
        val productCategory: ProductCategory? = null
    ) : ScanTierResult

    /** Tiers 1-3 all missed or were low-confidence; UI must show the Tier 4 manual overlay. */
    data class ManualFallbackRequired(val boundingBox: BoundingBox?) : ScanTierResult

    /**
     * No object in frame yet, or an object hasn't stayed in frame across enough consecutive
     * frames to trust it yet. The UI must make no changes at all — this is what keeps a stray
     * first frame (or a moment of camera shake) from flashing a result, or burning a cloud
     * request, before the user has actually settled the camera on an item.
     */
    data object Pending : ScanTierResult

    /**
     * The object-presence streak just crossed its threshold on this exact frame; the coordinator
     * is about to attempt real resolution (OCR, then cloud vision, then the on-device fallback)
     * on the *next* frame, which may include a multi-second network round-trip. The UI shows a
     * loading spinner for this state so that wait isn't silent.
     */
    data object Analyzing : ScanTierResult
}
