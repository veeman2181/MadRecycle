package com.ecomadison.app.ml

import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BarcodeTierImpl @Inject constructor() : BarcodeTier {

    private val scanner = BarcodeScanning.getClient()

    override suspend fun scan(image: InputImage): String? {
        val barcodes = scanner.process(image).await()
        return barcodes.firstOrNull { it.format in RETAIL_FORMATS }?.rawValue
    }

    private companion object {
        val RETAIL_FORMATS = setOf(
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8
        )
    }
}
