package com.ecomadison.app.data.local

import android.content.Context
import com.ecomadison.app.data.local.dao.RecyclableItemDao
import com.ecomadison.app.data.local.entity.RecyclableItemEntity
import com.ecomadison.app.domain.model.MaterialType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SeedRecyclableDto(
    val barcode: String,
    val itemName: String,
    val materialType: String,
    val rulesText: String,
    val minDimensionInches: Float? = null,
    val requiresFlatten: Boolean,
    val requires3D: Boolean,
    val isRecyclableAsIs: Boolean = false
)

/**
 * Phase 1 stands in for the Phase 3 `/v1/rules/madison` sync channel with a bundled asset,
 * loaded once into Room on first launch. See §8 Phase 1 exit criteria: "seeded local rule set".
 */
class SeedDataLoader(
    private val context: Context,
    private val dao: RecyclableItemDao,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return

        val raw = context.assets.open(SEED_ASSET_NAME).bufferedReader().use { it.readText() }
        val now = System.currentTimeMillis()
        val entities = json.decodeFromString<List<SeedRecyclableDto>>(raw).map { dto ->
            RecyclableItemEntity(
                barcode = dto.barcode,
                itemName = dto.itemName,
                materialType = MaterialType.valueOf(dto.materialType),
                rulesText = dto.rulesText,
                minDimensionInches = dto.minDimensionInches,
                requiresFlatten = dto.requiresFlatten,
                requires3D = dto.requires3D,
                isRecyclableAsIs = dto.isRecyclableAsIs,
                lastUpdatedTimestamp = now
            )
        }
        dao.insertAll(entities)
    }

    private companion object {
        const val SEED_ASSET_NAME = "seed_recyclables.json"
    }
}
