package com.ecomadison.app.ml

import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.ProductCategory

/**
 * A material guess from a CV tier (on-device or cloud), paired with the model's own confidence.
 * [productCategory] is an optional finer-grained guess (see [ProductCategory]) for display-only
 * trust-building -- disposal rule resolution always keys off [materialType], never this field.
 */
data class MaterialClassification(
    val materialType: MaterialType,
    val confidence: Float,
    val productCategory: ProductCategory? = null
)
