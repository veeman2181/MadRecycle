package com.ecomadison.app.domain.repository

import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RecyclableItem
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for disposal rules (§4.1). Room is authoritative; a future Phase 3 sync
 * client would upsert into the same table this interface reads from — callers never see the
 * network, they only ever observe Room.
 */
interface RulesRepository {

    /**
     * Tier 1 (barcode) lookup. Emits the cached row immediately, `null` on unknown barcode
     * (a miss here is the Tier 2/3/4 trigger, not an error — see ScanPipelineCoordinator).
     */
    fun getDisposalRule(barcode: String): Flow<RecyclableItem?>

    /**
     * Tiers 2-4 resolve a [MaterialType] guess rather than a barcode; those map to the seeded
     * fallback row keyed by `barcode == ""` for that material (see RecyclableItem schema note).
     */
    fun getFallbackRule(materialType: MaterialType): Flow<RecyclableItem?>

    /** True if every cached row is older than the 24h staleness window (§3.2). */
    suspend fun isCacheExpired(): Boolean
}
