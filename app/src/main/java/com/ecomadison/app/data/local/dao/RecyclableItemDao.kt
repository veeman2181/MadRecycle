package com.ecomadison.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ecomadison.app.data.local.entity.RecyclableItemEntity
import com.ecomadison.app.domain.model.MaterialType
import kotlinx.coroutines.flow.Flow

@Dao
interface RecyclableItemDao {

    @Query("SELECT * FROM madison_recyclables WHERE barcode = :barcode LIMIT 1")
    fun observeByBarcode(barcode: String): Flow<RecyclableItemEntity?>

    /** Tiers 2-4 fallback row for a material, conventionally keyed by barcode = "" (see entity doc). */
    @Query("SELECT * FROM madison_recyclables WHERE barcode = '' AND materialType = :materialType LIMIT 1")
    fun observeFallbackForMaterial(materialType: MaterialType): Flow<RecyclableItemEntity?>

    @Query("SELECT MAX(lastUpdatedTimestamp) FROM madison_recyclables")
    suspend fun getMostRecentUpdateTimestamp(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RecyclableItemEntity>)

    @Query("SELECT COUNT(*) FROM madison_recyclables")
    suspend fun count(): Int
}
