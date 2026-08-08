package com.ecomadison.app.domain.repository

import com.ecomadison.app.domain.model.ScanLogEntry

interface ScanLogRepository {
    suspend fun logScan(entry: ScanLogEntry)
}
