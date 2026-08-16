package com.debritsu.app.data

/**
 * Carries the source list from the detail screen to the player.
 *
 * It used to travel as a serialised Intent extra, which works until a title
 * returns a few hundred sources. Each one holds a debrid URL of around 1,500
 * characters, so a busy episode produces hundreds of kilobytes against a Binder
 * transaction limit of one megabyte for the whole launch. Over that line the
 * activity start fails inside system_server and the app is torn down with no
 * exception, no tombstone and nothing in the log to say why.
 *
 * Both screens live in the same process, so a handoff needs no serialising at
 * all. If the process dies in between, this empties and the player simply shows
 * no Sources button — which is the correct outcome, since the list is gone.
 */
object SourceHandoff {
    var streams: List<StreamOption> = emptyList()
        private set

    fun offer(list: List<StreamOption>) {
        streams = list
    }

    fun take(): List<StreamOption> = streams
}
