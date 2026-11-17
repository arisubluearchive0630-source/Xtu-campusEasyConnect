package com.example.android_logininternet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.android_logininternet.ui.HomeScreen
import com.example.android_logininternet.ui.theme.Android_logininternetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Android_logininternetTheme {
                HomeScreen()
            }
        }
    }
}
