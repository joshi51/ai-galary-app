package com.localphotoai.photomanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.localphotoai.photomanager.core.ui.theme.PhotoManagerTheme
import com.localphotoai.photomanager.navigation.PhotoManagerNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val systemIsDark = isSystemInDarkTheme()
            PhotoManagerTheme(darkTheme = themeMode.resolveIsDark(systemIsDark)) {
                PhotoManagerNavHost()
            }
        }
    }
}
