package com.plaintextlab.aistudio

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
                AppRoot(viewModel(), viewModel())
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Image("Image", Icons.Filled.AutoAwesome),
    Thumbnail("Thumbnail", Icons.Filled.Movie),
    Chat("Chat", Icons.Filled.Chat),
}

/** Three tabs (Image, Thumbnail, Chat) sharing the settings, the face and the server. */
@Composable
private fun AppRoot(studio: StudioViewModel, chat: ChatViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(studio.server.isBlank()) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            val settings = { showSettings = true }
            when (Tab.entries[tab]) {
                Tab.Image -> StudioScreen(studio, Mode.Image, settings, onEditInImageTab = {})
                Tab.Thumbnail -> StudioScreen(studio, Mode.Thumbnail, settings,
                    onEditInImageTab = { tab = Tab.Image.ordinal })
                Tab.Chat -> ChatScreen(
                    chat,
                    onOpenSettings = settings,
                    onUsePrompt = { studio.imageDraft = it; tab = Tab.Image.ordinal },
                    onUseForThumbnail = { studio.thumbnailIdea = it; tab = Tab.Thumbnail.ordinal },
                )
            }
        }
    }
    if (showSettings) {
        SettingsDialog(studio, onClose = { showSettings = false; chat.syncWithPc(); studio.syncWithPc() })
    }
}

/** Material 3, with the phone's wallpaper colors on Android 12+. */
@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
