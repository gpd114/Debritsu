package com.debritsu.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * An image from the network, with a placeholder until it arrives.
 *
 * Starts from whatever is already decoded so a poster that has been seen once
 * draws immediately rather than flashing its placeholder on every scroll.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Int = 6,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: String = ""
) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(Posters.cached(url)) }

    LaunchedEffect(url) {
        if (image == null) image = Posters.load(url)
    }

    Box(
        modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(PosterPlaceholder),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else if (fallback.isNotEmpty()) {
            // The first letters of the title, so a row without its artwork is
            // still identifiable rather than an empty rectangle.
            Text(
                fallback.take(2).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = PosterInk
            )
        }
    }
}

private val PosterPlaceholder = androidx.compose.ui.graphics.Color(0xFF2A2140)
private val PosterInk = androidx.compose.ui.graphics.Color(0xFF6B6480)
