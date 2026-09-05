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
}

// Generated rather than hard-coded, so the id is not committed and survives an
// update — the same arrangement the Android build has.
val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val clientId = anilistProps.getProperty("desktopClientId")
        ?: anilistProps.getProperty("clientId", "")
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
        }
    }
}
