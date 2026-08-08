package com.ecomadison.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ecomadison.app.data.local.entity.ScanLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanLogDao {

    @Insert
    suspend fun insert(entry: ScanLogEntity): Long

    @Query("SELECT * FROM scan_log WHERE userId = :userId ORDER BY timestamp DESC")
    fun observeForUser(userId: String): Flow<List<ScanLogEntity>>
}
