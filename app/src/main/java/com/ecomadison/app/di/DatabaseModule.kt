package com.ecomadison.app.di

import android.content.Context
import androidx.room.Room
import com.ecomadison.app.data.local.EcoMadisonDatabase
import com.ecomadison.app.data.local.SeedDataLoader
import com.ecomadison.app.data.local.dao.PointsLedgerDao
import com.ecomadison.app.data.local.dao.PropertyOrgDao
import com.ecomadison.app.data.local.dao.RecyclableItemDao
import com.ecomadison.app.data.local.dao.ScanLogDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EcoMadisonDatabase =
        Room.databaseBuilder(context, EcoMadisonDatabase::class.java, EcoMadisonDatabase.DATABASE_NAME)
            // No Phase 1 users/real data yet; madison_recyclables re-seeds itself from assets on
            // next read (SeedDataLoader.seedIfEmpty), so a destructive migration is simpler and
            // safer than hand-authoring an ALTER TABLE for every schema tweak at this stage.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideRecyclableItemDao(database: EcoMadisonDatabase): RecyclableItemDao = database.recyclableItemDao()

    @Provides
    fun providePropertyOrgDao(database: EcoMadisonDatabase): PropertyOrgDao = database.propertyOrgDao()

    @Provides
    fun provideScanLogDao(database: EcoMadisonDatabase): ScanLogDao = database.scanLogDao()

    @Provides
    fun providePointsLedgerDao(database: EcoMadisonDatabase): PointsLedgerDao = database.pointsLedgerDao()

    @Provides
    @Singleton
    fun provideSeedDataLoader(
        @ApplicationContext context: Context,
        dao: RecyclableItemDao
    ): SeedDataLoader = SeedDataLoader(context, dao)
}
