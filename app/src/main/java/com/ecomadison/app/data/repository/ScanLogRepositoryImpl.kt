package com.ecomadison.app.data.repository

import com.ecomadison.app.data.local.dao.ScanLogDao
import com.ecomadison.app.data.mapper.toEntity
import com.ecomadison.app.domain.model.ScanLogEntry
import com.ecomadison.app.domain.repository.ScanLogRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanLogRepositoryImpl @Inject constructor(
    private val dao: ScanLogDao
) : ScanLogRepository {

    override suspend fun logScan(entry: ScanLogEntry) {
        dao.insert(entry.toEntity())
    }
}
