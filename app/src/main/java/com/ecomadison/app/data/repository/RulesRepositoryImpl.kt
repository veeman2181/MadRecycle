package com.ecomadison.app.data.repository

import com.ecomadison.app.data.local.SeedDataLoader
import com.ecomadison.app.data.local.dao.RecyclableItemDao
import com.ecomadison.app.data.mapper.toDomain
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RecyclableItem
import com.ecomadison.app.domain.repository.RulesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §4.1 read path. Room is the only source read here; a Phase 3 sync client would upsert into the
 * same `madison_recyclables` table via [RecyclableItemDao.insertAll] and this class would need no
 * changes — callers already only ever observe Room.
 */
@Singleton
class RulesRepositoryImpl @Inject constructor(
    private val dao: RecyclableItemDao,
    private val seedDataLoader: SeedDataLoader
) : RulesRepository {

    override fun getDisposalRule(barcode: String): Flow<RecyclableItem?> =
        dao.observeByBarcode(barcode)
            .onStart { seedDataLoader.seedIfEmpty() }
            .map { it?.toDomain() }

    override fun getFallbackRule(materialType: MaterialType): Flow<RecyclableItem?> =
        dao.observeFallbackForMaterial(materialType)
            .onStart { seedDataLoader.seedIfEmpty() }
            .map { it?.toDomain() }

    override suspend fun isCacheExpired(): Boolean {
        val mostRecent = dao.getMostRecentUpdateTimestamp() ?: return true
        return System.currentTimeMillis() - mostRecent > CACHE_TTL_MILLIS
    }

    private companion object {
        const val CACHE_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
