package com.ecomadison.app.ml

import com.ecomadison.app.domain.model.MaterialType

/** A material guess from a CV tier (on-device or cloud), paired with the model's own confidence. */
data class MaterialClassification(val materialType: MaterialType, val confidence: Float)
