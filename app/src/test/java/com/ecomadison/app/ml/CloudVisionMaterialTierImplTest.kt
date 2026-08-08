package com.ecomadison.app.ml

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Documents the deliberate stub contract: no cloud vision vendor/API key is chosen yet (see
 * SPEC.md §2), so this tier always declines. If this test starts failing, it means the stub was
 * replaced with a real implementation — update/remove this test as part of that change, not before.
 */
class CloudVisionMaterialTierImplTest {

    @Test
    fun `always returns null until a vendor is wired up`() = runTest {
        val tier = CloudVisionMaterialTierImpl()

        assertThat(tier.classify(mockk<Bitmap>())).isNull()
    }
}
