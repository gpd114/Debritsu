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
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Debritsu"
            packageVersion = "0.1.0"
            // Built from the Android launcher artwork by tools-make-icon.ps1.
            // Without this the executable carries Compose's own stock icon,
            // which says nothing about what the program is.
            windows { iconFile.set(project.file("icon.ico")) }
        }
    }
}
