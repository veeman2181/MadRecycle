package com.ecomadison.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ecomadison.app.data.local.entity.RecyclableItemEntity
import com.ecomadison.app.domain.model.MaterialType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecyclableItemDaoTest {

    private lateinit var database: EcoMadisonDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EcoMadisonDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `observeByBarcode returns the seeded row for a known barcode`() = runTest {
        val dao = database.recyclableItemDao()
        dao.insertAll(listOf(jug("041303001505")))

        val result = dao.observeByBarcode("041303001505").first()

        assertThat(result?.materialType).isEqualTo(MaterialType.PLASTIC_JUG)
    }

    @Test
    fun `observeByBarcode returns null on an unknown barcode, not an error`() = runTest {
        val dao = database.recyclableItemDao()

        assertThat(dao.observeByBarcode("does-not-exist").first()).isNull()
    }

    @Test
    fun `observeFallbackForMaterial resolves the barcode-less fallback row for a material`() = runTest {
        val dao = database.recyclableItemDao()
        dao.insertAll(listOf(jug("041303001505"), jug("")))

        val result = dao.observeFallbackForMaterial(MaterialType.PLASTIC_JUG).first()

        assertThat(result?.barcode).isEqualTo("")
    }

    private fun jug(barcode: String) = RecyclableItemEntity(
        barcode = barcode,
        itemName = "Plastic Jug",
        materialType = MaterialType.PLASTIC_JUG,
        rulesText = "Keep 3D",
        minDimensionInches = null,
        requiresFlatten = false,
        requires3D = true,
        lastUpdatedTimestamp = System.currentTimeMillis()
    )
}
