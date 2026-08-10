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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * §5.5 acceptance: "Tier 1 must short-circuit Tiers 2-4 entirely when a barcode is decoded," plus
 * the object-presence gate that keeps a single noisy frame from burning a cloud request, and the
 * cloud-first / on-device-fallback resolution order (see ScanPipelineCoordinator's doc comment).
 */
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
    fun `no object in frame yields Pending without running OCR, classifier, or cloud vision`() = runTest {
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns null

        val result = coordinator.analyze(image, bitmap)

        assertThat(result).isEqualTo(ScanTierResult.Pending)
        coVerify(exactly = 0) { ocrTier.recognizeMaterial(any()) }
        coVerify(exactly = 0) { materialClassifierTier.classify(any()) }
        coVerify(exactly = 0) { cloudVisionMaterialTier.classify(any()) }
    }

    @Test
    fun `object presence gate must cross before any resolution tier is attempted`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns MaterialType.DRINK_CARTON

        val first = coordinator.analyze(image, bitmap)
        val second = coordinator.analyze(image, bitmap)
        val third = coordinator.analyze(image, bitmap)
        val fourth = coordinator.analyze(image, bitmap)

        assertThat(first).isEqualTo(ScanTierResult.Pending)
        assertThat(second).isEqualTo(ScanTierResult.Pending)
        assertThat(third).isEqualTo(ScanTierResult.Analyzing)
        assertThat(fourth).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.DRINK_CARTON, boundingBox))
        // OCR (and every other resolution tier) is untouched until the gate-crossing frame's
        // follow-up -- not spent on any of the three gating frames.
        coVerify(exactly = 1) { ocrTier.recognizeMaterial(any()) }
        coVerify(exactly = 0) { materialClassifierTier.classify(any()) }
        coVerify(exactly = 0) { cloudVisionMaterialTier.classify(any()) }
    }

    @Test
    fun `ocr hit resolves without ever attempting cloud vision or the on-device classifier`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns MaterialType.CARDBOARD

        val result = crossGateThenResolve()

        assertThat(result).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.CARDBOARD, boundingBox))
        coVerify(exactly = 0) { cloudVisionMaterialTier.classify(any()) }
        coVerify(exactly = 0) { materialClassifierTier.classify(any()) }
    }

    @Test
    fun `ocr miss falls through to cloud vision, which resolves it`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        every { networkMonitor.isConnected() } returns true
        coEvery { cloudVisionMaterialTier.classify(bitmap) } returns
            MaterialClassification(MaterialType.PLASTIC_JUG, confidence = 0.9f)

        val result = crossGateThenResolve()

        assertThat(result).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.PLASTIC_JUG, boundingBox))
        coVerify(exactly = 0) { materialClassifierTier.classify(any()) }
    }

    @Test
    fun `no connectivity skips cloud vision entirely and falls straight to the on-device classifier`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        every { networkMonitor.isConnected() } returns false
        coEvery { materialClassifierTier.classify(bitmap) } returns
            MaterialClassification(MaterialType.METAL_CAN, confidence = 0.8f)

        val result = crossGateThenResolve()

        assertThat(result).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.METAL_CAN, boundingBox))
        coVerify(exactly = 0) { cloudVisionMaterialTier.classify(any()) }
    }

    @Test
    fun `cloud vision error falls through to the on-device classifier`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        every { networkMonitor.isConnected() } returns true
        coEvery { cloudVisionMaterialTier.classify(bitmap) } returns null
        coEvery { materialClassifierTier.classify(bitmap) } returns
            MaterialClassification(MaterialType.GLASS, confidence = 0.85f)

        val result = crossGateThenResolve()

        assertThat(result).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.GLASS, boundingBox))
    }

    @Test
    fun `cloud vision timing out falls through to the on-device classifier rather than hanging`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        every { networkMonitor.isConnected() } returns true
        coEvery { cloudVisionMaterialTier.classify(bitmap) } coAnswers {
            delay(10_000) // exceeds the coordinator's own cloud-vision timeout
            MaterialClassification(MaterialType.PLASTIC_FILM, confidence = 0.7f)
        }
        coEvery { materialClassifierTier.classify(bitmap) } returns
            MaterialClassification(MaterialType.PLASTIC_FILM, confidence = 0.6f)

        val result = crossGateThenResolve()

        assertThat(result).isEqualTo(ScanTierResult.MaterialResolved(MaterialType.PLASTIC_FILM, boundingBox))
    }

    @Test
    fun `every resolution tier missing requires the manual fallback overlay`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returns boundingBox
        coEvery { ocrTier.recognizeMaterial(image) } returns null
        every { networkMonitor.isConnected() } returns true
        coEvery { cloudVisionMaterialTier.classify(bitmap) } returns null
        coEvery { materialClassifierTier.classify(bitmap) } returns null

        val result = crossGateThenResolve()

        assertThat(result).isEqualTo(ScanTierResult.ManualFallbackRequired(boundingBox))
    }

    @Test
    fun `losing the object mid-gate resets the streak instead of carrying it over`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returnsMany
            listOf(boundingBox, null, boundingBox, boundingBox, boundingBox, boundingBox)
        coEvery { ocrTier.recognizeMaterial(image) } returns MaterialType.CARDBOARD

        val results = List(6) { coordinator.analyze(image, bitmap) }

        assertThat(results).isEqualTo(
            listOf(
                ScanTierResult.Pending, // streak = 1
                ScanTierResult.Pending, // object lost -- streak reset to 0
                ScanTierResult.Pending, // streak = 1
                ScanTierResult.Pending, // streak = 2
                ScanTierResult.Analyzing, // streak = 3, gate crossed
                ScanTierResult.MaterialResolved(MaterialType.CARDBOARD, boundingBox)
            )
        )
    }

    @Test
    fun `losing the object between the gate crossing and resolution abandons that attempt`() = runTest {
        val boundingBox = BoundingBox(widthInches = 4f, heightInches = 4f)
        coEvery { barcodeTier.scan(image) } returns null
        coEvery { objectDetectionTier.detect(image) } returnsMany
            listOf(boundingBox, boundingBox, boundingBox, null, boundingBox, boundingBox, boundingBox)
        coEvery { ocrTier.recognizeMaterial(image) } returns MaterialType.CARDBOARD

        val results = List(7) { coordinator.analyze(image, bitmap) }

        assertThat(results).isEqualTo(
            listOf(
                ScanTierResult.Pending,
                ScanTierResult.Pending,
                ScanTierResult.Analyzing, // gate crossed, about to resolve next frame
                ScanTierResult.Pending, // object vanished before resolution ran -- attempt abandoned
                ScanTierResult.Pending, // streak restarts from scratch
                ScanTierResult.Pending,
                ScanTierResult.Analyzing
            )
        )
        // The abandoned attempt never actually reached OCR -- only the second, completed streak does.
        coVerify(exactly = 0) { ocrTier.recognizeMaterial(any()) }
    }

    /** Advances past the object-presence gate (3 frames), then returns the 4th frame's result -- the actual resolution attempt. */
    private suspend fun crossGateThenResolve(): ScanTierResult {
        repeat(3) { coordinator.analyze(image, bitmap) }
        return coordinator.analyze(image, bitmap)
    }
}
