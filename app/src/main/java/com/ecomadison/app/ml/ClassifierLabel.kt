package com.ecomadison.app.ml

import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.ProductCategory

/**
 * One line of `material_classifier_labels.txt`, resolved to whichever granularity the bundled
 * model was trained at. Lets [MaterialClassifierTierImpl] work unmodified whether the asset is
 * the current 7-class MaterialType-only model or a future finer-grained one trained per
 * tools/material_classifier/train_granular_experiment.py (product-name folders + one collapsed
 * OTHER for everything non-recyclable).
 */
sealed interface ClassifierLabel {
    val materialType: MaterialType

    data class Product(val category: ProductCategory) : ClassifierLabel {
        override val materialType: MaterialType get() = category.materialType
    }

    data class MaterialOnly(override val materialType: MaterialType) : ClassifierLabel
}

/** Tries the finer [ProductCategory] taxonomy first; falls back to a plain [MaterialType] (e.g. "OTHER"). */
fun parseClassifierLabel(raw: String): ClassifierLabel {
    val name = raw.trim().uppercase()
    return runCatching { ClassifierLabel.Product(ProductCategory.valueOf(name)) }
        .getOrElse { ClassifierLabel.MaterialOnly(MaterialType.valueOf(name)) }
}
