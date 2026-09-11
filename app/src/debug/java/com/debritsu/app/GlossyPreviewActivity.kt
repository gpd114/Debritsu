package com.debritsu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.Anime
import com.debritsu.app.ui.AiringStrip
import com.debritsu.app.ui.DebritsuTheme
import com.debritsu.app.ui.DetailScreen
import com.debritsu.app.ui.DownloadsScreen
import com.debritsu.app.ui.SettingsScreen
import com.debritsu.app.ui.HomeTopBar
import com.debritsu.app.ui.Ink
import com.debritsu.app.ui.Shelf
import com.debritsu.app.ui.glossyBackdrop

/**
 * Debug builds only: the Home screen's pieces drawn with sample shows, so the
 * look can be checked when AniList is unreachable or the device has no
 * account. Start it with
 *
 *     adb shell am start -n com.debritsu.app/.GlossyPreviewActivity
 *
 * Covers come from Kitsu's CDN. Nothing here is reachable from a release.
 */
class GlossyPreviewActivity : ComponentActivity() {

    private fun poster(kitsu: Int) = "https://media.kitsu.app/anime/poster_images/$kitsu/medium.jpg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val watching = listOf(
            Anime(1, "Attack on Titan", poster(7442), episodes = 25, progress = 8, averageScore = 84),
            Anime(2, "One Punch Man", poster(10740), episodes = 12, progress = 3, averageScore = 83,
                nextEpisode = 4, airingInSeconds = 3 * 86_400),
            Anime(3, "Demon Slayer: Kimetsu no Yaiba", poster(41370), episodes = 26, progress = 5,
                averageScore = 86, nextEpisode = 12, airingInSeconds = 6 * 86_400),
            Anime(4, "Witch Hat Atelier", poster(46043), episodes = 13, progress = 6,
                averageScore = 86, nextEpisode = 7, airingInSeconds = 5 * 3_600),
            Anime(5, "Hunter x Hunter (2011)", poster(6448), episodes = 148, progress = 21, averageScore = 84)
        )
        val trending = listOf(
            Anime(11, "My Hero Academia", poster(11469), episodes = 13, averageScore = 83),
            Anime(12, "One Piece", poster(12), averageScore = 84),
            Anime(13, "Death Note", poster(1376), episodes = 37, averageScore = 83),
            Anime(14, "Fullmetal Alchemist: Brotherhood", poster(3936), episodes = 64, averageScore = 83),
            Anime(15, "Bleach", poster(244), episodes = 366, averageScore = 77),
            Anime(16, "Assassination Classroom", poster(8640), episodes = 22)
        )
        val airing = watching
            .filter { it.nextEpisode != null }
            .sortedBy { it.airingInSeconds }

        // `--es screen detail|settings|downloads` shows that screen instead of Home;
        // `player` opens the real player on a public test clip (Big Buck Bunny,
        // Creative Commons), so its controls can be seen over moving pictures.
        val screen = intent.getStringExtra("screen")
        if (screen == "player") {
            startActivity(
                android.content.Intent(this, com.debritsu.app.player.PlayerActivity::class.java)
                    .putExtra(
                        com.debritsu.app.player.PlayerActivity.EXTRA_URL,
                        "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"
                    )
                    .putExtra(com.debritsu.app.player.PlayerActivity.EXTRA_TITLE, "Sample — EP 2")
                    .putExtra(com.debritsu.app.player.PlayerActivity.EXTRA_EPISODE, 2)
                    .putExtra(com.debritsu.app.player.PlayerActivity.EXTRA_EPISODE_COUNT, 12)
            )
            finish()
            return
        }
        val sample = Anime(
            7442, "Attack on Titan", poster(7442),
            banner = "https://media.kitsu.app/anime/cover_images/7442/large.jpg",
            episodes = 25, progress = 7, listStatus = "CURRENT", score = 9.0,
            description = "Centuries ago, mankind was slaughtered to near extinction by monstrous " +
                "humanoid creatures called titans, forcing humans to hide in fear behind " +
                "enormous concentric walls. What makes these giants truly terrifying is that " +
                "their taste for human flesh is not born out of hunger but what appears to be " +
                "out of pleasure.",
            averageScore = 84, popularity = 1, favourites = 51_203,
            genres = listOf("Action", "Drama", "Fantasy", "Mystery"), studio = "Wit Studio",
            format = "TV", seasonLabel = "SPRING 2013", airingStatus = "Finished", durationMins = 24
        )

        setContent {
            DebritsuTheme {
                Surface(
                    color = Color.Transparent,
                    contentColor = Ink.Bone,
                    modifier = Modifier.fillMaxSize().glossyBackdrop()
                ) {
                    when (screen) {
                        "detail" -> { DetailScreen(anilistId = sample.id, onBack = {}, preview = sample); return@Surface }
                        "settings" -> { SettingsScreen(onBack = {}); return@Surface }
                        "downloads" -> { DownloadsScreen(onBack = {}); return@Surface }
                    }
                    var query by remember { mutableStateOf("") }
                    Column(
                        Modifier
                            .statusBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 28.dp)
                    ) {
                        HomeTopBar(query, { query = it }, {}, {})
                        AiringStrip(airing) {}
                        Shelf("Continue watching", watching, {}) {}
                        Shelf("Trending", trending, {}) {}
                    }
                }
            }
        }
    }
}
