package com.ecomadison.app.ui

import android.content.Context
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RecyclableItem
import com.ecomadison.app.domain.model.RuleMessage
import com.ecomadison.app.domain.model.ScanLogEntry
import com.ecomadison.app.domain.usecase.GetDisposalRuleUseCase
import com.ecomadison.app.domain.usecase.GetFallbackRuleUseCase
import com.ecomadison.app.domain.usecase.LogScanUseCase
import com.ecomadison.app.domain.usecase.ResolveDisplayRuleUseCase
import com.ecomadison.app.ml.ScanPipelineCoordinator
import com.ecomadison.app.ui.scanner.ScannerIntent
import com.ecomadison.app.ui.scanner.ScannerViewModel
import com.ecomadison.app.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ScannerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scanPipelineCoordinator: ScanPipelineCoordinator = mockk()
    private val getDisposalRuleUseCase: GetDisposalRuleUseCase = mockk()
    private val getFallbackRuleUseCase: GetFallbackRuleUseCase = mockk()
    private val resolveDisplayRuleUseCase = ResolveDisplayRuleUseCase()
    private val logScanUseCase: LogScanUseCase = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    private fun buildViewModel() = ScannerViewModel(
        scanPipelineCoordinator = scanPipelineCoordinator,
        getDisposalRuleUseCase = getDisposalRuleUseCase,
        getFallbackRuleUseCase = getFallbackRuleUseCase,
        resolveDisplayRuleUseCase = resolveDisplayRuleUseCase,
        logScanUseCase = logScanUseCase,
        context = context
    )

    private val jugItem = RecyclableItem(
        barcode = "",
        itemName = "Plastic Jug/Bottle (fallback)",
        materialType = MaterialType.PLASTIC_JUG,
        rulesText = "Keep 3D",
        minDimensionInches = null,
        requiresFlatten = false,
        requires3D = true,
        isRecyclableAsIs = false,
        lastUpdatedTimestamp = 0L
    )

    @Test
    fun `manual tier 4 selection resolves the rule and logs tier 4 with no point penalty`() = runTest {
        coEvery { getFallbackRuleUseCase(MaterialType.PLASTIC_JUG) } returns flowOf(jugItem)
        val viewModel = buildViewModel()

        viewModel.onIntent(ScannerIntent.ManualMaterialSelected(MaterialType.PLASTIC_JUG))

        assertThat(viewModel.uiState.value.ruleMessage).isEqualTo(RuleMessage.Keep3D)
        assertThat(viewModel.uiState.value.ruleDetail).isEqualTo("Keep 3D")
        assertThat(viewModel.uiState.value.resolvedFromTier).isEqualTo(4)
        coVerify {
            logScanUseCase(match<ScanLogEntry> { it.resolvedByTier == 4 && it.pointsAwarded == 0 })
        }
    }

    @Test
    fun `blank rulesText surfaces no detail line rather than an empty one`() = runTest {
        coEvery { getFallbackRuleUseCase(MaterialType.CARDBOARD) } returns flowOf(jugItem.copy(materialType = MaterialType.CARDBOARD, rulesText = "", requiresFlatten = true, requires3D = false))
        val viewModel = buildViewModel()

        viewModel.onIntent(ScannerIntent.ManualMaterialSelected(MaterialType.CARDBOARD))

        assertThat(viewModel.uiState.value.ruleMessage).isEqualTo(RuleMessage.Flatten)
        assertThat(viewModel.uiState.value.ruleDetail).isNull()
    }

    @Test
    fun `manual selection with no seeded fallback row still shows generic guidance, never blank`() = runTest {
        coEvery { getFallbackRuleUseCase(MaterialType.OTHER) } returns flowOf(null)
        val viewModel = buildViewModel()

        viewModel.onIntent(ScannerIntent.ManualMaterialSelected(MaterialType.OTHER))

        assertThat(viewModel.uiState.value.ruleMessage).isEqualTo(RuleMessage.GenericFallback)
    }

    @Test
    fun `rule dismissed resets ui state so scanning can resume`() = runTest {
        coEvery { getFallbackRuleUseCase(MaterialType.PLASTIC_JUG) } returns flowOf(jugItem)
        val viewModel = buildViewModel()
        viewModel.onIntent(ScannerIntent.ManualMaterialSelected(MaterialType.PLASTIC_JUG))
        assertThat(viewModel.uiState.value.ruleMessage).isNotNull()

        viewModel.onIntent(ScannerIntent.RuleDismissed)

        assertThat(viewModel.uiState.value.ruleMessage).isNull()
        assertThat(viewModel.uiState.value.isAwaitingManualSelection).isFalse()
    }
}
