plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin, the Compose compiler plugin and the serialization plugin move as
    // one — the Compose compiler plugin is versioned with Kotlin, not with
    // Compose, and Compose Multiplatform requires it at the same version as the
    // Kotlin plugin. Changing one alone produces a compiler that disagrees with
    // itself about what it generated.
    //
    // On 2.1 rather than 2.0.20 because Compose Multiplatform 1.8.0 and later
    // require Kotlin 2.1.0 as a minimum, and the desktop build planned in
    // WINDOWS-PORT.md shares this toolchain. Nothing else moves with it: AGP
    // stays at 8.5.2, Gradle at 8.9 and compileSdk at 34, which keeps
    // androidx.tv:tv-material on the 1.0.x line it is deliberately pinned to.
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
}
