package com.ecomadison.app.ml

import androidx.test.core.app.ApplicationProvider
import com.ecomadison.app.domain.model.MaterialType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real bundled `assets/material_classifier_*` files (mirrors [OcrKeywordDictionaryTest]'s
 * "asset, not code" contract). Deliberately does NOT call [MaterialClassifierTierImpl.classify]:
 * TFLite's Interpreter loads a native (.so) library per-ABI, which isn't available to a local JVM
 * Robolectric test on this host — the same reason [OcrTierImpl]'s real ML Kit inference has no unit
 * test either. Real inference is only exercisable on-device/emulator.
 */
@RunWith(RobolectricTestRunner::class)
class MaterialClassifierTierImplTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `labels asset is non-empty and every line is a valid MaterialType`() {
        val labels = context.assets.open("material_classifier_labels.txt")
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }

        assertThat(labels).isNotEmpty()
        labels.forEach { label ->
            // Throws IllegalArgumentException (failing the test) if the asset drifts from the enum.
            MaterialType.valueOf(label.trim())
        }
    }

    @Test
    fun `tflite model asset is bundled and non-empty`() {
        val size = context.assets.open("material_classifier_v1.tflite").use { it.available() }

        assertThat(size).isGreaterThan(0)
    }
}
