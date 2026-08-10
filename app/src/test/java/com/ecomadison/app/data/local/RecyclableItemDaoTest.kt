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

    /**
     * Regression test: every seeded fallback row shares `barcode = ""` (one per MaterialType).
     * With `barcode` as the sole @PrimaryKey and OnConflictStrategy.REPLACE, inserting all of
     * them in one batch silently dropped every fallback row except the last one in the list —
     * only whichever material happened to be seeded last could ever resolve via Tiers 2-4.
     */
    @Test
    fun `multiple fallback rows for different materials all survive the same insert batch`() = runTest {
        val dao = database.recyclableItemDao()
        dao.insertAll(
            listOf(
                fallbackRow(MaterialType.CARDBOARD),
                fallbackRow(MaterialType.PLASTIC_JUG),
                fallbackRow(MaterialType.METAL_CAN),
                fallbackRow(MaterialType.DRINK_CARTON),
                fallbackRow(MaterialType.GLASS),
                fallbackRow(MaterialType.PAPER)
            )
        )

        listOf(
            MaterialType.CARDBOARD,
            MaterialType.PLASTIC_JUG,
            MaterialType.METAL_CAN,
            MaterialType.DRINK_CARTON,
            MaterialType.GLASS,
            MaterialType.PAPER
        ).forEach { materialType ->
            val result = dao.observeFallbackForMaterial(materialType).first()
            assertThat(result?.materialType).isEqualTo(materialType)
        }
    }

    private fun jug(barcode: String) = RecyclableItemEntity(
        barcode = barcode,
        itemName = "Plastic Jug",
        materialType = MaterialType.PLASTIC_JUG,
        rulesText = "Keep 3D",
        minDimensionInches = null,
        requiresFlatten = false,
        requires3D = true,
        isRecyclableAsIs = false,
        lastUpdatedTimestamp = System.currentTimeMillis()
    )

    private fun fallbackRow(materialType: MaterialType) = RecyclableItemEntity(
        barcode = "",
        itemName = "$materialType (fallback)",
        materialType = materialType,
        rulesText = "",
        minDimensionInches = null,
        requiresFlatten = false,
        requires3D = false,
        isRecyclableAsIs = false,
        lastUpdatedTimestamp = System.currentTimeMillis()
    )
}
