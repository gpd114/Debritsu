package com.debritsu.desktop

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogLevel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Throwaway: pauses and resumes a file over and over and counts the silence
 * libVLC inserts on resuming ("playback way too early ... playing silence"),
 * once per audio output module named on the command line.
 *
 * gradle :desktop:resumeProbe -PprobeFile="C:\path\to\file.mkv" -PprobeAouts=mmdevice,directsound
 */
fun main(args: Array<String>) {
    val file = args[0]
    val aouts = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.split(',') ?: listOf("")
    val cycles = args.getOrNull(2)?.toIntOrNull() ?: 12
    Vlc.prepare() ?: error("no VLC")
    if (aouts.first().startsWith("app")) { for (v in aouts) appPlayer(file, cycles, v.removePrefix("app").split(" ").filter { it.isNotBlank() }); return }

    for (aout in aouts) {
        val opts = mutableListOf("--intf=dummy", "--avcodec-hw=none", "--vout=dummy", "--network-caching=3000")
        if (aout.isNotBlank()) opts += "--aout=$aout"
        val factory = MediaPlayerFactory(*opts.toTypedArray())
        val events = AtomicInteger()
        val silenceUs = AtomicLong()
        val log = factory.application().newLog()
        log.level = LogLevel.WARNING
        val early = Regex("""way too early \((-?\d+)\)""")
        log.addLogListener { _, _, _, _, _, _, _, message ->
            early.find(message)?.let {
                events.incrementAndGet()
                silenceUs.addAndGet(-it.groupValues[1].toLong())
            }
            if ("aout" in message || "too late" in message && "picture" !in message) println("  [$aout] $message")
        }
        val player = factory.mediaPlayers().newMediaPlayer()
        player.media().play(file)
        Thread.sleep(6000)
        player.audio().setVolume(30)
        println("aout=${aout.ifBlank { "default" }} settled, starting $cycles cycles")
        events.set(0); silenceUs.set(0)
        val perCycle = mutableListOf<Int>()
        repeat(cycles) {
            player.controls().setPause(true)
            Thread.sleep(2500)
            val before = events.get()
            player.controls().setPause(false)
            Thread.sleep(3500)
            perCycle += events.get() - before
        }
        println(
            "RESULT aout=${aout.ifBlank { "default" }}: resumes with silence " +
                "${perCycle.count { it > 0 }}/$cycles, events ${events.get()}, " +
                "total silence ${silenceUs.get() / 1000} ms"
        )
        player.controls().stop()
        player.release()
        log.release()
        factory.release()
    }
}

/** The same count through the app's own player, frame copies and all. */
private fun appPlayer(file: String, cycles: Int, extra: List<String>) {
    val events = AtomicInteger()
    val silenceUs = AtomicLong()
    val early = Regex("""way too (?:early|late) \((-?\d+)\)""")
    com.debritsu.app.data.BuildInfo.log = { tag, message ->
        early.find(message)?.let {
            events.incrementAndGet()
            silenceUs.addAndGet(kotlin.math.abs(it.groupValues[1].toLong()))
        }
    }
    val player = VlcPlayer(Vlc.directory()!!, extra)
    player.play(file, emptyList(), 0L, "", "")
    Thread.sleep(6000)
    player.setVolume(30)
    println("app player settled at ${player.positionMs()}ms, starting $cycles cycles")
    events.set(0); silenceUs.set(0)
    val perCycle = mutableListOf<Int>()
    repeat(cycles) {
        player.setPaused(true)
        Thread.sleep(2500)
        val before = events.get()
        player.setPaused(false)
        Thread.sleep(3500)
        perCycle += events.get() - before
    }
    println(
        "RESULT app $extra: resumes with silence ${perCycle.count { it > 0 }}/$cycles, " +
            "events ${events.get()}, total silence ${silenceUs.get() / 1000} ms"
    )
    player.release()
}
