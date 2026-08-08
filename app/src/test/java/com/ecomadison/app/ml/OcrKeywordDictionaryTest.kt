package com.ecomadison.app.ml

import androidx.test.core.app.ApplicationProvider
import com.ecomadison.app.domain.model.MaterialType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the real bundled `assets/ocr_keywords_v1.json` — the dictionary must stay a data asset, not Kotlin code. */
@RunWith(RobolectricTestRunner::class)
class OcrKeywordDictionaryTest {

    private val dictionary = OcrKeywordDictionary(ApplicationProvider.getApplicationContext())

    @Test
    fun `matches a specific keyword over a more generic overlapping one`() = runTest {
        assertThat(dictionary.match("100% OAT MILK - HALF GALLON")).isEqualTo(MaterialType.DRINK_CARTON)
    }

    @Test
    fun `matches a generic keyword when no specific phrase is present`() = runTest {
        assertThat(dictionary.match("VITAMIN D WHOLE MILK")).isEqualTo(MaterialType.PLASTIC_JUG)
    }

    @Test
    fun `returns null when no keyword matches`() = runTest {
        assertThat(dictionary.match("SPARKLING WATER")).isNull()
    }
}
