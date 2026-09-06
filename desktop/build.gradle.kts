import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The same anilist.properties the Android build reads, so one file holds the
// ids for every front end. A desktop client is a *different* client id from the
// phone's: AniList allows one redirect URL per client, and this one redirects to
// the pin page rather than to debritsu://auth.
val anilistProps = Properties().apply {
    val f = rootProject.file("anilist.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // compose.desktop.currentOs carries Material 2, and the phone and
    // television builds are both Material 3. Asked for by name so the three
    // look alike.
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    // For the native window handle of the canvas mpv draws into. Java has no
    // way of its own to hand out an HWND, and mpv's --wid needs one.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    // libVLC, which decodes into a buffer we paint ourselves — the only way to
    // get our own controls over the picture. mpv could not: with --wid it draws
    // into a window it does not own and receives no input, so nothing could be
    // laid over it and every control had to sit beside it.
    implementation("uk.co.caprica:vlcj:4.8.3")
}

// Generated rather than hard-coded, so the id is not committed and survives an
// update — the same arrangement the Android build has.
// Prefers the desktop client, falling back to the phone's so a checkout with
// only the old key still builds — it just cannot sign in, because that client
// redirects to debritsu://auth.
val anilistClientId: String = anilistProps.getProperty("desktopClientId")
    ?: anilistProps.getProperty("clientId", "")

/**
 * The version stamped into the installer, taken from the tag being built.
 *
 * An MSI version has to be plain numbers — jpackage refuses anything else and
 * fails the build rather than warning — so `desktop-v0.2.0` becomes `0.2.0` and
 * anything that is not three numbers falls back. The Android build takes its
 * versionName from RELEASE_TAG the same way, for the same reason: five
 * different binaries once shipped claiming the same version because the number
 * lived in a file somebody had to remember to edit.
 */
val desktopVersion: String = (System.getenv("RELEASE_TAG") ?: "")
    .removePrefix("desktop-v")
    .removePrefix("v")
    .takeIf { it.matches(Regex("""\d+(\.\d+){0,2}""")) }
    ?: "0.1.0"

val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val clientId = anilistClientId
    // Declared as an input or Gradle has nothing to compare and calls the task
    // up to date forever: the id was changed in anilist.properties and the
    // generated file kept the old one, which fails as a silently stale build
    // rather than as an error.
    inputs.property("clientId", clientId)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("com/debritsu/desktop")
        dir.mkdirs()
        dir.resolve("Generated.kt").writeText(
            """
            package com.debritsu.desktop

            /** Written by the build from anilist.properties. */
            internal const val ANILIST_CLIENT_ID: String = "$clientId"
            """.trimIndent() + "\n"
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo)
}

compose.desktop {
    application {
        mainClass = "com.debritsu.desktop.MainKt"

        // vlcj allocates the video buffers through sun.misc.Unsafe, reflecting
        // into java.nio.Buffer for its address field. On JDK 17 that is closed
        // by default, so the allocation throws inside a JNA callback — which
        // cannot propagate an exception and returns zero instead. libVLC reads
        // that as "no pictures", fails to build a video output, and retries
        // forever: audio plays and the screen stays black, with nothing in any
        // log that names the cause.
        //
        // Opening the module is the whole fix. It took four wrong guesses to
        // find because the failure is three layers from its cause.
        jvmArgs += listOf(
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
        )
        nativeDistributions {
            // jpackage builds a runtime out of the modules it can detect, and
            // it cannot detect a reflective one. vlcj allocates its video
            // buffers through sun.misc.Unsafe, which lives in jdk.unsupported;
            // without this the class is simply absent, the allocation throws
            // inside a JNA callback that cannot report it, and libVLC sees a
            // zero it reads as "no pictures".
            //
            // The symptom was a black screen with audio playing, and nothing in
            // libVLC's log, vlcj's behaviour or our own said the word "module".
            // It took a JNA callback exception handler to see it at all.
            modules("jdk.unsupported")

            targetFormats(TargetFormat.Msi)
            packageName = "Debritsu"
            packageVersion = desktopVersion
            description = "Anime player. Stremio addons, a debrid provider, AniList."
            vendor = "Debritsu"
            windows {
                // Built from the Android launcher artwork by tools-make-icon.ps1.
                // Without this the executable carries Compose's own stock icon,
                // which says nothing about what the program is.
                iconFile.set(project.file("icon.ico"))

                // jpackage creates neither of these unless asked, so the first
                // installer put Debritsu on the machine with no way to start it
                // except finding the exe in Program Files.
                shortcut = true
                menu = true
                menuGroup = "Debritsu"

                // Fixed, and must never change.
                //
                // This is what tells Windows that a new MSI replaces the
                // installed one rather than sitting beside it. jpackage invents
                // a UUID per build when none is given, so every release would
                // have been a separate program in Add or Remove Programs, all
                // called Debritsu, and uninstalling would have been a matter of
                // guessing which. Changing this later has the same effect.
                upgradeUuid = "81cb0241-7d19-43fd-9159-e506b6a9ca2e"
            }
        }
    }
}

// Throwaway: dumps where libVLC puts the picture inside its padded buffer.
// gradle :desktop:probe -PprobeFile="C:\path\to\file.mp4"
tasks.register<JavaExec>("probe") {
    mainClass.set("com.debritsu.desktop.FrameProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
    )
    args = listOf(
        project.findProperty("probeFile") as String? ?: "",
        project.findProperty("probeHw") as String? ?: "",
        project.findProperty("probeAt") as String? ?: "",
        project.findProperty("probePin") as String? ?: ""
    )
}
