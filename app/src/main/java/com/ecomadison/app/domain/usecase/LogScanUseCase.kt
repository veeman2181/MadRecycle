package com.ecomadison.app.domain.usecase

import com.ecomadison.app.domain.model.ScanLogEntry
import com.ecomadison.app.domain.repository.ScanLogRepository
import javax.inject.Inject

class LogScanUseCase @Inject constructor(
    private val scanLogRepository: ScanLogRepository
) {
    suspend operator fun invoke(entry: ScanLogEntry) = scanLogRepository.logScan(entry)
}
