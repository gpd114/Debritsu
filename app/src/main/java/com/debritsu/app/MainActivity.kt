package com.debritsu.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import com.debritsu.app.ui.Ink
import com.debritsu.app.ui.pageBackground
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
        handleAuth(intent)

        // Replay anything watched offline as soon as we're up.
        lifecycleScope.launch { runCatching { SyncQueue.flush() } }

        setContent {
            // Status and navigation icons follow the app's theme rather than the
            // phone's: dark on Pastel, light on Night and Plum. Following the
            // phone drew them dark on dark whenever the two disagreed.
            // Re-applied whenever the theme is switched in Settings.
            val dark = Ink.palette.dark
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            DebritsuTheme {
                // One backdrop behind every screen; each screen's own scaffold
                // is transparent so it shows through.
                Surface(
                    color = Color.Transparent,
                    contentColor = Ink.Bone,
                    modifier = Modifier.fillMaxSize().pageBackground()
                ) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onOpen = { nav.navigate("detail/$it") },
                                // Resume opens the show and plays its next
                                // episode straight away.
                                onResume = { nav.navigate("detail/$it?play=true") },
                                onSettings = { nav.navigate("settings") },
                                onDownloads = { nav.navigate("downloads") },
                                authFlash = authFlash
                            )
                        }
                        composable(
                            "detail/{id}?play={play}",
                            arguments = listOf(
                                navArgument("play") { type = NavType.BoolType; defaultValue = false }
                            )
                        ) { entry ->
                            DetailScreen(
                                anilistId = entry.arguments?.getString("id")?.toIntOrNull() ?: 0,
                                onBack = { nav.popBackStack() },
                                onOpen = { nav.navigate("detail/$it") },
                                autoPlay = entry.arguments?.getBoolean("play") == true
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


    /** AniList uses the implicit grant: the token comes back in the URL fragment. */
    private fun handleAuth(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "debritsu") return
        val fragment = data.fragment ?: return
        val token = fragment.split("&")
            .firstOrNull { it.startsWith("access_token=") }
            ?.removePrefix("access_token=")
        if (!token.isNullOrEmpty()) {
            Settings.aniListToken = token
            authFlash++
        }
    }
}
