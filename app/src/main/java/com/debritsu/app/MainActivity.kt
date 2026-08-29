package com.debritsu.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import com.debritsu.app.data.Settings
import com.debritsu.app.data.SyncQueue
import kotlinx.coroutines.launch
import com.debritsu.app.ui.DetailScreen
import com.debritsu.app.ui.DownloadsScreen
import com.debritsu.app.ui.HomeScreen
import com.debritsu.app.ui.DebritsuTheme
import com.debritsu.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private var authFlash by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAuth(intent)

        // Replay anything watched offline as soon as we're up.
        lifecycleScope.launch { runCatching { SyncQueue.flush() } }

        setContent {
            DebritsuTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onOpen = { nav.navigate("detail/$it") },
                                onSettings = { nav.navigate("settings") },
                                onDownloads = { nav.navigate("downloads") },
                                authFlash = authFlash
                            )
                        }
                        composable("detail/{id}") { entry ->
                            DetailScreen(
                                anilistId = entry.arguments?.getString("id")?.toIntOrNull() ?: 0,
                                onBack = { nav.popBackStack() },
                                onOpen = { nav.navigate("detail/$it") }
                            )
                        }
                        composable("downloads") {
                            DownloadsScreen(onBack = { nav.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuth(intent)
    }


    /** The deep-link return, still how a phone comes back from a real browser. */
    private fun handleAuth(intent: Intent?) {
        val token = AuthActivity.tokenFrom(intent?.data) ?: return
        Settings.aniListToken = token
        authFlash++
    }
}
