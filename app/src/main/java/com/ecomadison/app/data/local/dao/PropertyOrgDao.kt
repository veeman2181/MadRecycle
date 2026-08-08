package com.ecomadison.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ecomadison.app.data.local.entity.PropertyOrgEntity
import kotlinx.coroutines.flow.Flow

/** Schema-complete per §3.1; wired up by the Phase 3 property-binding flow (out of scope here). */
@Dao
interface PropertyOrgDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PropertyOrgEntity)

    @Query("SELECT * FROM property_org WHERE propertyCode = :propertyCode LIMIT 1")
    fun observeByCode(propertyCode: String): Flow<PropertyOrgEntity?>
}
