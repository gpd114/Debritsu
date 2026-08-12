package com.debritsu.app.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Uses the default media receiver, which plays any HTTP(S) URL — exactly what
 * a debrid link is. A custom receiver would only be needed for bespoke UI on
 * the TV, which is not worth the hosting requirement here.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_RECEIVER_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        // CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        const val DEFAULT_RECEIVER_ID = "CC1AD845"
    }
}
