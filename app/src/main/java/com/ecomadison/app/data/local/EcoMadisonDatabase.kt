package com.ecomadison.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ecomadison.app.data.local.dao.PointsLedgerDao
import com.ecomadison.app.data.local.dao.PropertyOrgDao
import com.ecomadison.app.data.local.dao.RecyclableItemDao
import com.ecomadison.app.data.local.dao.ScanLogDao
import com.ecomadison.app.data.local.entity.PointsLedgerEntity
import com.ecomadison.app.data.local.entity.PropertyOrgEntity
import com.ecomadison.app.data.local.entity.RecyclableItemEntity
import com.ecomadison.app.data.local.entity.ScanLogEntity

/**
 * Spec §3.1/§7.4: `madison_recyclables` is public rule data and does not require encryption.
 * `scan_log` and `points_ledger` contain userId + behavioral history and must be encrypted at
 * rest (SQLCipher) once Phase 2/3 lands — deferred here since Phase 1 has no real userId/auth yet.
 */
@Database(
    entities = [
        RecyclableItemEntity::class,
        PropertyOrgEntity::class,
        ScanLogEntity::class,
        PointsLedgerEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class EcoMadisonDatabase : RoomDatabase() {
    abstract fun recyclableItemDao(): RecyclableItemDao
    abstract fun propertyOrgDao(): PropertyOrgDao
    abstract fun scanLogDao(): ScanLogDao
    abstract fun pointsLedgerDao(): PointsLedgerDao

    companion object {
        const val DATABASE_NAME = "ecomadison.db"
    }
}
