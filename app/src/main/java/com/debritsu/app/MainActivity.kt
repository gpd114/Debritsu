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
import com.debritsu.app.ui.DebritsuTheme
import com.debritsu.app.ui.tv.TvDetailScreen
import com.debritsu.app.ui.tv.TvHomeScreen
import com.debritsu.app.ui.tv.TvSettingsScreen

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
                            // The television browse screen. The phone's Home is
                            // gone from this build rather than kept behind a
                            // check — the point of a separate application is
                            // that it does not carry the other one around.
                            TvHomeScreen(
                                onOpen = { nav.navigate("detail/$it") },
                                onSettings = { nav.navigate("settings") },
                                authFlash = authFlash
                            )
                        }
                        composable("detail/{id}") { entry ->
                            TvDetailScreen(
                                anilistId = entry.arguments?.getString("id")?.toIntOrNull() ?: 0,
                                onBack = { nav.popBackStack() },
                                onOpen = { nav.navigate("detail/$it") }
                            )
                        }
                        composable("settings") {
                            TvSettingsScreen(onBack = { nav.popBackStack() })
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
