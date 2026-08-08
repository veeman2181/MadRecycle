package com.ecomadison.app.domain.usecase

import com.ecomadison.app.domain.model.BoundingBox
import com.ecomadison.app.domain.model.RecyclableItem
import com.ecomadison.app.domain.model.RuleMessage
import javax.inject.Inject

/**
 * REQ-4.2.2 rule resolution, isolated from UI and ML so each row (and the negative case) is a
 * plain unit test. Size-gating only applies when the resolved item declares [RecyclableItem.minDimensionInches]
 * and a Tier 2 bounding box was actually measured.
 */
class ResolveDisplayRuleUseCase @Inject constructor() {

    operator fun invoke(item: RecyclableItem, dimensions: BoundingBox?): RuleMessage {
        val isTooSmall = item.minDimensionInches != null &&
            dimensions != null &&
            dimensions.smallestSideInches() < item.minDimensionInches

        return when {
            isTooSmall -> RuleMessage.TooSmall
            item.requires3D -> RuleMessage.Keep3D
            item.requiresFlatten -> RuleMessage.Flatten
            else -> RuleMessage.GenericFallback
        }
    }
}
