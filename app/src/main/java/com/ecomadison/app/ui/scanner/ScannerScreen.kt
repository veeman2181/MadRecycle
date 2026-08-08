package com.ecomadison.app.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.RuleMessage
import androidx.core.content.ContextCompat

@Composable
fun ScannerScreen(viewModel: ScannerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        CameraPermissionRationale(onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        return
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onFrame = { imageProxy -> viewModel.onIntent(ScannerIntent.FrameCaptured(imageProxy)) }
        )

        uiState.ruleMessage?.let { rule ->
            RuleResultOverlay(
                rule = rule,
                onScanAgain = { viewModel.onIntent(ScannerIntent.RuleDismissed) }
            )
        }

        if (uiState.isAwaitingManualSelection) {
            ManualFallbackOverlay(
                onMaterialSelected = { material ->
                    viewModel.onIntent(ScannerIntent.ManualMaterialSelected(material))
                }
            )
        }
    }
}

@Composable
private fun CameraPermissionRationale(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "EcoMadison needs camera access to scan items for disposal guidance.",
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 16.dp)) {
            Text("Grant camera permission")
        }
    }
}

@Composable
private fun RuleResultOverlay(rule: RuleMessage, onScanAgain: () -> Unit) {
    Dialog(onDismissRequest = onScanAgain) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = rule.emoji, style = MaterialTheme.typography.headlineMedium)
                Text(text = rule.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                Button(onClick = onScanAgain, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan Again")
                }
            }
        }
    }
}

private data class ManualOption(val label: String, val emoji: String, val materialType: MaterialType)

private val MANUAL_OPTIONS = listOf(
    ManualOption("Cardboard", "📦", MaterialType.CARDBOARD),
    ManualOption("Plastic Jug/Bottle", "🥤", MaterialType.PLASTIC_JUG),
    ManualOption("Metal Can", "🥫", MaterialType.METAL_CAN),
    ManualOption("Drink Carton", "🥛", MaterialType.DRINK_CARTON)
)

/** Tier 4 (§5.5): shown when Tiers 1-3 all fail or are low-confidence. Blocks on user input. */
@Composable
private fun ManualFallbackOverlay(onMaterialSelected: (MaterialType) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Black.copy(alpha = 0.6f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Couldn't identify this item — pick a match:",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(MANUAL_OPTIONS) { option ->
                        Button(
                            onClick = { onMaterialSelected(option.materialType) },
                            colors = ButtonDefaults.buttonColors()
                        ) {
                            Row {
                                Text(option.emoji + " " + option.label)
                            }
                        }
                    }
                }
            }
        }
    }
}
