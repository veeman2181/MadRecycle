package com.ecomadison.app.domain

import com.ecomadison.app.domain.model.BoundingBox
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RecyclableItem
import com.ecomadison.app.domain.model.RuleMessage
import com.ecomadison.app.domain.usecase.ResolveDisplayRuleUseCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** One case per REQ-4.2.2 row, plus the required negative case (never a blank state). */
class ResolveDisplayRuleUseCaseTest {

    private val resolveDisplayRule = ResolveDisplayRuleUseCase()

    private fun item(
        materialType: MaterialType = MaterialType.OTHER,
        minDimensionInches: Float? = null,
        requiresFlatten: Boolean = false,
        requires3D: Boolean = false,
        isRecyclableAsIs: Boolean = false
    ) = RecyclableItem(
        barcode = "000000000000",
        itemName = "Test Item",
        materialType = materialType,
        rulesText = "irrelevant",
        minDimensionInches = minDimensionInches,
        requiresFlatten = requiresFlatten,
        requires3D = requires3D,
        isRecyclableAsIs = isRecyclableAsIs,
        lastUpdatedTimestamp = 0L
    )

    @Test
    fun `plastic item under 3x3 footprint is too small`() {
        val result = resolveDisplayRule(
            item = item(materialType = MaterialType.OTHER, minDimensionInches = 3.0f),
            dimensions = BoundingBox(widthInches = 2f, heightInches = 2f)
        )

        assertThat(result).isEqualTo(RuleMessage.TooSmall)
    }

    @Test
    fun `plastic jug, can, or carton must be kept 3D`() {
        listOf(MaterialType.PLASTIC_JUG, MaterialType.METAL_CAN, MaterialType.DRINK_CARTON).forEach { material ->
            val result = resolveDisplayRule(
                item = item(materialType = material, requires3D = true),
                dimensions = null
            )
            assertThat(result).isEqualTo(RuleMessage.Keep3D)
        }
    }

    @Test
    fun `cardboard box must be flattened`() {
        val result = resolveDisplayRule(
            item = item(materialType = MaterialType.CARDBOARD, requiresFlatten = true),
            dimensions = null
        )

        assertThat(result).isEqualTo(RuleMessage.Flatten)
    }

    @Test
    fun `glass or paper marked recyclable as-is shows that message, not the generic fallback`() {
        listOf(MaterialType.GLASS, MaterialType.PAPER).forEach { material ->
            val result = resolveDisplayRule(
                item = item(materialType = material, isRecyclableAsIs = true),
                dimensions = null
            )
            assertThat(result).isEqualTo(RuleMessage.RecyclableAsIs)
        }
    }

    @Test
    fun `no rule match falls back to generic guidance, never blank`() {
        val result = resolveDisplayRule(
            item = item(materialType = MaterialType.PLASTIC_FILM),
            dimensions = null
        )

        assertThat(result).isEqualTo(RuleMessage.GenericFallback)
    }

    @Test
    fun `size gate is ignored when no bounding box was measured`() {
        val result = resolveDisplayRule(
            item = item(materialType = MaterialType.OTHER, minDimensionInches = 3.0f),
            dimensions = null
        )

        assertThat(result).isEqualTo(RuleMessage.GenericFallback)
    }

    @Test
    fun `item at or above the minimum dimension is not flagged too small`() {
        val result = resolveDisplayRule(
            item = item(materialType = MaterialType.OTHER, minDimensionInches = 3.0f),
            dimensions = BoundingBox(widthInches = 4f, heightInches = 5f)
        )

        assertThat(result).isEqualTo(RuleMessage.GenericFallback)
    }
}
