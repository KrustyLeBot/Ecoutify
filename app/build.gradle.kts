plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.ecoutify"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ecoutify"
        minSdk = 26
        targetSdk = 35
        // The CI run number grows with every build, so each build can be installed over the previous one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    signingConfigs {
        // The key is committed on purpose (personal app): every build is signed with the same key,
        // which is what lets Android install an update over the installed app.
        // Replace it with a secret key before any public distribution.
        create("shared") {
            storeFile = file("ecoutify.jks")
            storePassword = "android"
            keyAlias = "ecoutify"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses Java 8+ APIs (java.time, etc.)
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // YouTube extraction (search, audio streams, similar tracks): no account, no ads.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Background playback + media notification
    val media3 = "1.6.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // UI
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
