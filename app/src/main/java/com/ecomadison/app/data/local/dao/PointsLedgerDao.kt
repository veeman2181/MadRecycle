package com.ecomadison.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ecomadison.app.data.local.entity.PointsLedgerEntity
import kotlinx.coroutines.flow.Flow

/** Schema-complete per §3.1; wired up by the Phase 3/§5.4 gamification flow (out of scope here). */
@Dao
interface PointsLedgerDao {

    @Insert
    suspend fun insert(entity: PointsLedgerEntity): Long

    @Query("SELECT * FROM points_ledger WHERE userId = :userId AND monthYear = :monthYear")
    fun observeForMonth(userId: String, monthYear: String): Flow<List<PointsLedgerEntity>>
}
