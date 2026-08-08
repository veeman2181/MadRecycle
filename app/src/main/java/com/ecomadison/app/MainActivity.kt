package com.ecomadison.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ecomadison.app.ui.scanner.ScannerScreen
import com.ecomadison.app.ui.theme.EcoMadisonTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EcoMadisonTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScannerScreen()
                }
            }
        }
    }
}
