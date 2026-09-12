package com.debritsu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.debritsu.app.data.Anime
import com.debritsu.app.ui.DebritsuTheme
import com.debritsu.app.ui.applyTheme
import com.debritsu.app.ui.tv.DebritsuTvTheme
import com.debritsu.app.ui.tv.TvDetailScreen
import com.debritsu.app.ui.tv.TvHomeFeed
import com.debritsu.app.ui.tv.TvSettingsScreen

/**
 * Debug builds only: the television screens drawn with sample shows, so the
 * look can be checked on a device with no AniList account. Start it with
 *
 *     adb shell am start -n com.debritsu.tv/com.debritsu.app.TvPreviewActivity \
 *         [--es screen detail|settings] [--es theme night|plum]
 *
 * Home and Detail are drawn from the samples below, Settings is the real
 * screen. The samples carry
 * real AniList ids, so the wide art is looked up on ani.zip exactly as the app
 * does it. Covers come from Kitsu's CDN. Nothing here is reachable from a
 * release.
 */
class TvPreviewActivity : ComponentActivity() {

    private fun poster(kitsu: Int) = "https://media.kitsu.app/anime/poster_images/$kitsu/medium.jpg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme(intent.getStringExtra("theme") ?: "pastel")
        // The emulator image carries a phone's status bar, which a television
        // does not, and it takes 24dp off a screen whose height is budgeted to
        // the dp. Hidden, the preview measures what the box will show.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        val watching = listOf(
            Anime(16498, "Attack on Titan", poster(7442), episodes = 25, progress = 7, averageScore = 84),
            Anime(21459, "My Hero Academia", poster(11469), episodes = 13, progress = 8, averageScore = 76),
            Anime(21087, "One Punch Man", poster(10740), episodes = 12, progress = 2, averageScore = 83,
                nextEpisode = 4, airingInSeconds = 3 * 86_400),
            Anime(101922, "Demon Slayer: Kimetsu no Yaiba", poster(41370), episodes = 26, progress = 4,
                averageScore = 83, nextEpisode = 12, airingInSeconds = 6 * 86_400),
            Anime(11061, "Hunter x Hunter (2011)", poster(6448), episodes = 148, progress = 20, averageScore = 89,
                nextEpisode = 21, airingInSeconds = 5 * 3_600)
        )
        val trending = listOf(
            Anime(21, "One Piece", poster(12), averageScore = 88),
            Anime(1535, "Death Note", poster(1376), episodes = 37, averageScore = 84),
            Anime(5114, "Fullmetal Alchemist: Brotherhood", poster(3936), episodes = 64, averageScore = 90),
            Anime(269, "Bleach", poster(244), episodes = 366, averageScore = 76),
            Anime(20755, "Assassination Classroom", poster(8640), episodes = 22, averageScore = 80)
        )

        val sample = Anime(
            16498, "Attack on Titan", poster(7442),
            episodes = 25, progress = 7, listStatus = "CURRENT",
            description = "Centuries ago, mankind was slaughtered to near extinction by monstrous " +
                "humanoid creatures called titans, forcing humans to hide in fear behind " +
                "enormous concentric walls. What makes these giants truly terrifying is that " +
                "their taste for human flesh is not born out of hunger but what appears to be " +
                "out of pleasure.",
            averageScore = 84, popularity = 1, favourites = 51_203,
            genres = listOf("Action", "Drama", "Fantasy", "Mystery"), studio = "Wit Studio",
            format = "TV", seasonLabel = "SPRING 2013", airingStatus = "Finished", durationMins = 24
        )

        val screen = intent.getStringExtra("screen")

        setContent {
            DebritsuTheme {
                DebritsuTvTheme {
                    when (screen) {
                        "detail" -> TvDetailScreen(anilistId = sample.id, onBack = { finish() }, onOpen = {}, preview = sample)
                        "settings" -> TvSettingsScreen(onBack = { finish() })
                        else -> {
                            var focused by remember { mutableStateOf<Anime?>(null) }
                            TvHomeFeed(
                                watching = watching,
                                planning = emptyList(),
                                trending = trending,
                                recommended = trending.reversed(),
                                focusedShow = focused,
                                onFocusShow = { focused = it },
                                searchActive = false,
                                query = "",
                                onQuery = {},
                                searchFocus = remember { FocusRequester() },
                                found = emptyList(),
                                searching = false,
                                needsSource = false,
                                onStartSearch = {},
                                onOpen = {},
                                onResume = {},
                                onSettings = {}
                            )
                        }
                    }
                }
            }
        }
    }
}
