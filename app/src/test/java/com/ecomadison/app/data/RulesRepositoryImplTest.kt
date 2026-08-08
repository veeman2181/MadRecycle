package com.ecomadison.app.data

import app.cash.turbine.test
import com.ecomadison.app.data.local.SeedDataLoader
import com.ecomadison.app.data.local.dao.RecyclableItemDao
import com.ecomadison.app.data.local.entity.RecyclableItemEntity
import com.ecomadison.app.data.repository.RulesRepositoryImpl
import com.ecomadison.app.domain.model.MaterialType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class RulesRepositoryImplTest {

    private val dao: RecyclableItemDao = mockk()
    private val seedDataLoader: SeedDataLoader = mockk(relaxed = true)
    private lateinit var repository: RulesRepositoryImpl

    private val entity = RecyclableItemEntity(
        barcode = "12345",
        itemName = "Test Jug",
        materialType = MaterialType.PLASTIC_JUG,
        rulesText = "Keep 3D",
        minDimensionInches = null,
        requiresFlatten = false,
        requires3D = true,
        lastUpdatedTimestamp = 1_000L
    )

    @Before
    fun setUp() {
        repository = RulesRepositoryImpl(dao, seedDataLoader)
    }

    @Test
    fun `getDisposalRule seeds on first collection and maps the cached row to a domain model`() = runTest {
        every { dao.observeByBarcode("12345") } returns flowOf(entity)

        repository.getDisposalRule("12345").test {
            val result = awaitItem()
            assertThat(result?.itemName).isEqualTo("Test Jug")
            assertThat(result?.requires3D).isTrue()
            awaitComplete()
        }
        coVerify { seedDataLoader.seedIfEmpty() }
    }

    @Test
    fun `getDisposalRule emits null on an unknown barcode without erroring`() = runTest {
        every { dao.observeByBarcode("unknown") } returns flowOf(null)

        repository.getDisposalRule("unknown").test {
            assertThat(awaitItem()).isNull()
            awaitComplete()
        }
    }

    @Test
    fun `isCacheExpired is true when the table is empty`() = runTest {
        coEvery { dao.getMostRecentUpdateTimestamp() } returns null

        assertThat(repository.isCacheExpired()).isTrue()
    }

    @Test
    fun `isCacheExpired is false within the 24h staleness window`() = runTest {
        coEvery { dao.getMostRecentUpdateTimestamp() } returns System.currentTimeMillis()

        assertThat(repository.isCacheExpired()).isFalse()
    }

    @Test
    fun `isCacheExpired is true past the 24h staleness window`() = runTest {
        val twentyFiveHoursAgo = System.currentTimeMillis() - (25L * 60 * 60 * 1000)
        coEvery { dao.getMostRecentUpdateTimestamp() } returns twentyFiveHoursAgo

        assertThat(repository.isCacheExpired()).isTrue()
    }
}
