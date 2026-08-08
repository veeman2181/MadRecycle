package com.ecomadison.app.ml

import android.graphics.Bitmap
import com.ecomadison.app.domain.model.BoundingBox
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.network.NetworkMonitor
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.vision.common.InputImage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** §5.5 acceptance: "Tier 1 must short-circuit Tiers 2-4 entirely when a barcode is decoded." */
class ScanPipelineCoordinatorTest {

    private val barcodeTier: BarcodeTier = mockk()
    private val objectDetectionTier: ObjectDetectionTier = mockk()
    private val ocrTier: OcrTier = mockk()
    private val materialClassifierTier: MaterialClassifierTier = mockk()
    private val cloudVisionMaterialTier: CloudVisionMaterialTier = mockk()
    private val networkMonitor: NetworkMonitor = mockk()
    private val image: InputImage = mockk()
    private val bitmap: Bitmap = mockk()

    private lateinit var coordinator: ScanPipelineCoordinator

    @Before
    fun setUp() {
        coordinator = ScanPipelineCoordinator(
            barcodeTier,
            objectDetectionTier,
            ocrTier,
            materialClassifierTier,
            cloudVisionMaterialTier,
            networkMonitor
        )
    }

    @Test
    fun `barcode hit short-circuits every other tier entirely`() = runTest {
        coEvery { barcodeTier.scan(image) } returns "041303001505"

        val result = coordinator.analyze(image, bitmap)

        assertThat(result).isEqualTo(ScanTierResult.BarcodeResolved("041303001505"))
        coVerify(exactly = 0) { objectDetectionTier.detect(any()) }
        coVerify(exactly = 0) { ocrTier.recognizeMaterial(any()) }
        coVerify(exactly = 0) { materialClassifierTier.classify(any()) }
        coVerify(exactly = 0) { cloudVisionMaterialTier.classify(any()) }
    }

    @Test
    fun `barcode miss falls through to object detection and a confident ocr match`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns MaterialType.DRINK_CARTON

        val result = coordinator.analyze(image, bitmap)

        assertThat(result).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.DRINK_CARTON, boundingBox))
        coVerify(exactly = 0) { materialClassifierTier.classify(any()) }
        coVerify(exactly = 0) { cloudVisionMaterialTier.classify(any()) }
    }

    @Test
    fun `ocr miss falls through to the on-device classifier`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        coEvery { materialClassifierTier.classify(bitmap) } returns
            MaterialClassification(MaterialType.CARDBOARD, confidence = 0.9f)

        val result = coordinator.analyze(image, bitmap)

        assertThat(result).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.CARDBOARD, boundingBox))
        coVerify(exactly = 0) { cloudVisionMaterialTier.classify(any()) }
    }

    @Test
    fun `classifier miss with connectivity falls through to the cloud backup`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        coEvery { materialClassifierTier.classify(bitmap) } returns null
        every { networkMonitor.isConnected() } returns true
        coEvery { cloudVisionMaterialTier.classify(bitmap) } returns
            MaterialClassification(MaterialType.PLASTIC_FILM, confidence = 0.8f)

        val result = coordinator.analyze(image, bitmap)

        assertThat(result).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.PLASTIC_FILM, boundingBox))
    }

    @Test
    fun `classifier miss without connectivity never attempts the cloud backup`() = runTest {
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns null
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        coEvery { materialClassifierTier.classify(bitmap) } returns null
        every { networkMonitor.isConnected() } returns false

        val result = coordinator.analyze(image, bitmap)

        assertThat(result).isEqualTo(ScanTierResult.ManualFallbackRequired(null))
        coVerify(exactly = 0) { cloudVisionMaterialTier.classify(any()) }
    }

    @Test
    fun `all tiers missing requires the manual fallback overlay`() = runTest {
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns null
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        coEvery { materialClassifierTier.classify(bitmap) } returns null
        every { networkMonitor.isConnected() } returns true
        coEvery { cloudVisionMaterialTier.classify(bitmap) } returns null

        val result = coordinator.analyze(image, bitmap)

        assertThat(result).isEqualTo(ScanTierResult.ManualFallbackRequired(null))
    }
}
