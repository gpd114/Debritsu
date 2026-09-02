import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Your AniList client ID lives in anilist.properties, which is yours alone and
// is never shipped in project updates — so it survives every upgrade.
val anilistProps = Properties().apply {
    val f = rootProject.file("anilist.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.debritsu.app"
    compileSdk = 34

    defaultConfig {
        // The television build is its own application, so it installs beside
        // the phone one rather than replacing it — the same box can hold both,
        // and neither can be blocked by the other's signing key. They keep
        // separate settings and downloads as a consequence, which is the price
        // of that and worth naming.
        applicationId = "com.debritsu.tv"
        minSdk = 26
        targetSdk = 34
        // Bump for every release. Android compares versionCode when deciding
        // whether one build may replace another, and it is the only way to tell
        // two APKs apart once installed — five different builds once shipped as
        // 31 because the tag was moved rather than the version raised, and
        // working out which one was on a phone meant unzipping it.
        // Versioned independently of the phone app: this is a different
        // application id, so its numbering answers to nobody but itself.
        versionCode = 6
        // Taken from the tag being built where there is one, so a release can
        // never report a name that disagrees with its own tag. Falls back to
        // the literal for local builds, which have no tag. Television releases
        // are tagged tv-v*, to keep them apart from the phone's v* tags.
        versionName = (System.getenv("RELEASE_TAG")?.removePrefix("tv-v"))
            ?.takeIf { it.isNotBlank() }
            ?: "0.1.0"

        buildConfigField(
            "String",
            "ANILIST_CLIENT_ID",
            "\"${anilistProps.getProperty("clientId", "")}\""
        )
    }

    signingConfigs {
        create("release") {
            // Populated by CI from repository secrets. Falls back to the debug
            // key locally so the project still builds without a keystore.
            val storePath = System.getenv("KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                if (System.getenv("KEYSTORE_PATH") != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose for TV. Its Card and Surface carry focus handling of their own —
    // scaling, borders, and correct d-pad behaviour — which is the part that was
    // hand-written and got wrong on the phone branch.
    // Pinned to 1.0.x deliberately: 1.1.0 demands compileSdk 35 and Android
    // Gradle plugin 8.6, and drags in a Compose newer than the bill of
    // materials this project pins. That upgrade is worth making on its own
    // terms, not as a side effect of adding a dependency.
    implementation("androidx.tv:tv-material:1.0.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-cast:1.4.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
