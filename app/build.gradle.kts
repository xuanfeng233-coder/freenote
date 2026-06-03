import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// BraynLabs Software signing identity (see keystore.properties at project root).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.ncmdecrypt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.braynlabs.freenote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // Sign with the BraynLabs identity when keystore.properties is present (see the
    // .example template). Without it the project still builds: debug uses the default
    // Android debug keystore and release is produced unsigned — so anyone can clone & build.
    val hasKeystore = keystorePropertiesFile.exists()

    signingConfigs {
        if (hasKeystore) {
            create("braynlabs") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("braynlabs")
        }
        release {
            isMinifyEnabled = true
            if (hasKeystore) signingConfig = signingConfigs.getByName("braynlabs")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // In-app player (background playback + lock-screen / notification controls).
    // Media3 ExoPlayer decodes the decrypted FLAC/MP3/OGG/AAC/WAV directly.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    // Apple-Music-style motion helpers.
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0") // spring physics
    implementation("androidx.palette:palette-ktx:1.0.0")               // album-art colour extraction

    // Audio metadata (tag) read/write for FLAC / MP3 / OGG / M4A.
    implementation("net.jthink:jaudiotagger:3.0.1")
}
