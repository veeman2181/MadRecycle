package com.ecomadison.app.domain.usecase

import com.ecomadison.app.domain.model.RecyclableItem
import com.ecomadison.app.domain.repository.RulesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Tier 1 entry point: barcode -> cached rule, or null on a miss (feeds Tier 2). */
class GetDisposalRuleUseCase @Inject constructor(
    private val rulesRepository: RulesRepository
) {
    operator fun invoke(barcode: String): Flow<RecyclableItem?> =
        rulesRepository.getDisposalRule(barcode)
}
