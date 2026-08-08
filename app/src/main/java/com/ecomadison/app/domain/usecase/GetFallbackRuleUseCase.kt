package com.ecomadison.app.domain.usecase

import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RecyclableItem
import com.ecomadison.app.domain.repository.RulesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Tiers 2-4 resolve a [MaterialType] guess (no barcode) to the seeded generic rule for that material. */
class GetFallbackRuleUseCase @Inject constructor(
    private val rulesRepository: RulesRepository
) {
    operator fun invoke(materialType: MaterialType): Flow<RecyclableItem?> =
        rulesRepository.getFallbackRule(materialType)
}
