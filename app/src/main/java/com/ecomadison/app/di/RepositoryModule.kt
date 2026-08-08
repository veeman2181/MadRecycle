package com.ecomadison.app.di

import com.ecomadison.app.data.repository.RulesRepositoryImpl
import com.ecomadison.app.data.repository.ScanLogRepositoryImpl
import com.ecomadison.app.domain.repository.RulesRepository
import com.ecomadison.app.domain.repository.ScanLogRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindRulesRepository(impl: RulesRepositoryImpl): RulesRepository

    @Binds
    @Singleton
    abstract fun bindScanLogRepository(impl: ScanLogRepositoryImpl): ScanLogRepository
}
