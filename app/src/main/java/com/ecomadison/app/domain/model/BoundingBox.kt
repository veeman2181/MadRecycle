package com.ecomadison.app.domain.model

import kotlin.math.min

/** Physical-world footprint of a detected object (Tier 2 output), converted from pixel bounds via a calibrated reference. */
data class BoundingBox(
    val widthInches: Float,
    val heightInches: Float
) {
    fun smallestSideInches(): Float = min(widthInches, heightInches)
}
