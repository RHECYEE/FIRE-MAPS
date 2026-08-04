package com.rhecyee.firelinemap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rhecyee.firelinemap.ui.FirelineApp
import com.rhecyee.firelinemap.ui.theme.FirelineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FirelineTheme {
                FirelineApp()
            }
        }
    }
}
