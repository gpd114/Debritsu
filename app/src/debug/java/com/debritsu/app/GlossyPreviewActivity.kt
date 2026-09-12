package com.debritsu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.Anime
import com.debritsu.app.ui.DebritsuTheme
import com.debritsu.app.ui.DetailScreen
import com.debritsu.app.ui.DownloadsScreen
import com.debritsu.app.ui.HomeFeed
import com.debritsu.app.ui.Ink
import com.debritsu.app.ui.SettingsScreen
import com.debritsu.app.ui.applyTheme
import com.debritsu.app.ui.pageBackground

/**
 * Debug builds only: the screens drawn with sample shows, so the look can be
 * checked when AniList is unreachable or the device has no account. Start it with
 *
 *     adb shell am start -n com.debritsu.app/.GlossyPreviewActivity \
 *         [--es screen detail|settings|downloads|player] [--es theme night|plum]
 *
 * The samples carry real AniList ids, so the wide art is looked up on ani.zip
 * exactly as the app does it. Covers come from Kitsu's CDN. Nothing here is
 * reachable from a release.
 */
class GlossyPreviewActivity : ComponentActivity() {

    private fun poster(kitsu: Int) = "https://media.kitsu.app/anime/poster_images/$kitsu/medium.jpg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme(intent.getStringExtra("theme") ?: "pastel")
        val bars = if (Ink.palette.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)

        // `--es screen detail|settings|downloads` shows that screen instead of Home;
        // `player` opens the real player on a public test clip (Big Buck Bunny,
        // Creative Commons), so its controls can be seen over moving pictures.
        val screen = intent.getStringExtra("screen")
        if (screen == "player") {
            // Two sample sources, so the Sources button shows and its picker can
            // be seen. Both point at the same public clip; nothing is resolved.
            val clip = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"
            com.debritsu.app.data.SourceHandoff.offer(
                listOf(
                    com.debritsu.app.data.StreamOption(
                        "Sample addon", "Sample 720p", "Big Buck Bunny 720p · 1 MB", clip, null, null
                    ),
                    com.debritsu.app.data.StreamOption(
                        "Sample addon", "Sample 1080p", "Big Buck Bunny 1080p · 2 MB", clip, null, null
                    )
                )
            )
            startActivity(
                android.content.Intent(this, com.debritsu.app.player.PlayerActivity::class.java)
                    .putExtra(
                        com.debritsu.app.player.PlayerActivity.EXTRA_URL,
                        "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"
                    )
                    .putExtra(com.debritsu.app.player.PlayerActivity.EXTRA_TITLE, "Sample — EP 2")
                    .putExtra(com.debritsu.app.player.PlayerActivity.EXTRA_EPISODE, 2)
                    .putExtra(com.debritsu.app.player.PlayerActivity.EXTRA_EPISODE_COUNT, 12)
                    .putExtra(com.debritsu.app.player.PlayerActivity.EXTRA_SOURCE_INDEX, 0)
            )
            finish()
            return
        }

        // AniList ids, with covers from Kitsu.
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
        val recommended = listOf(
            Anime(104578, "Attack on Titan Season 3 Part 2", poster(41982), episodes = 10, averageScore = 90),
            Anime(21856, "My Hero Academia Season 2", poster(12268), episodes = 25, averageScore = 80),
            Anime(20789, "The Seven Deadly Sins", poster(8699), episodes = 24, averageScore = 75),
            Anime(147105, "Witch Hat Atelier", poster(46043), episodes = 13, averageScore = 86)
        )
        val sample = Anime(
            16498, "Attack on Titan", poster(7442),
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
                    modifier = Modifier.fillMaxSize().pageBackground()
                ) {
                    when (screen) {
                        "detail" -> DetailScreen(anilistId = sample.id, onBack = {}, preview = sample)
                        "settings" -> SettingsScreen(onBack = {})
                        "downloads" -> DownloadsScreen(onBack = {})
                        else -> HomeFeed(
                            watching = watching,
                            planning = emptyList(),
                            trending = trending,
                            recommended = recommended,
                            loading = false,
                            needsSource = false,
                            bottomPadding = 0.dp,
                            onOpen = {}, onResume = {}, onExpand = {},
                            onSearch = {}, onDownloads = {}, onSettings = {}
                        )
                    }
                }
            }
        }
    }
}
