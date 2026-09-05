pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "Debritsu"
include(":app")

// Everything that talks to an addon, a debrid provider or AniList, with no
// Android in it. A plain Kotlin JVM library rather than a multiplatform one:
// Android and the planned Windows build are both JVM, so the extra machinery
// would buy nothing. See WINDOWS-PORT.md on the `windows` branch.
include(":shared")

// The Windows build. Compose Desktop over the same :shared module the phone and
// television apps use, with mpv doing the playing.
include(":desktop")
