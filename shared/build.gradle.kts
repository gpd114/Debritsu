plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // Matches the app's compileOptions. A mismatch here surfaces as an
    // inscrutable dex error rather than as a version complaint.
    jvmToolchain(17)
}

dependencies {
    // api rather than implementation: the app builds OkHttp requests and
    // decodes JSON with these types itself, so they belong to this module's
    // public surface rather than being an internal detail of it.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
