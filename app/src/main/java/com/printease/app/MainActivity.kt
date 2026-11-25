package com.printease.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appearance = getSharedPreferences("printease-appearance", MODE_PRIVATE)
        setContent {
            var preference by remember {
                mutableStateOf(
                    if (appearance.contains("dark")) appearance.getBoolean("dark", false) else null
                )
            }
            val systemDark = isSystemInDarkTheme()
            val dark = preference ?: systemDark
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            PrintEaseTheme(dark = dark) {
                val model: PrintViewModel = viewModel()
                LaunchedEffect(intent.getStringExtra("orderId")) {
                    intent.getStringExtra("orderId")?.let { model.openOrder(it) }
                }
                PrintEase(
                    model,
                    dark = dark,
                    onToggleTheme = {
                        val next = !dark
                        preference = next
                        appearance.edit().putBoolean("dark", next).apply()
                    },
                )
            }
        }
    }
}
