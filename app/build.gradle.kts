import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing identity. Prefer keeping the real properties file
// outside the repository and pointing Gradle at it with either:
//   FREENOTE_KEYSTORE_PROPERTIES=/path/to/keystore.properties
//   -PfreenoteKeystoreProperties=/path/to/keystore.properties
val configuredKeystorePropertiesPath = providers.gradleProperty("freenoteKeystoreProperties")
    .orElse(providers.environmentVariable("FREENOTE_KEYSTORE_PROPERTIES"))
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val keystorePropertiesFile = configuredKeystorePropertiesPath
    ?.let { rootProject.file(it) }
    ?: rootProject.file("keystore.properties")
if (configuredKeystorePropertiesPath != null && !keystorePropertiesFile.exists()) {
    throw GradleException(
        "Configured keystore properties file does not exist: ${keystorePropertiesFile.absolutePath}"
    )
}
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}
fun resolveKeystoreStoreFile(path: String): File {
    val storeFile = File(path)
    return if (storeFile.isAbsolute) storeFile else keystorePropertiesFile.parentFile.resolve(path)
}

android {
    namespace = "com.ncmdecrypt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.braynlabs.freenote"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    // Sign with the configured identity when keystore.properties is present (see
    // the .example template). Without it the project still builds: debug uses
    // the default Android debug keystore and release is produced unsigned.
    val hasKeystore = keystorePropertiesFile.exists()

    signingConfigs {
        if (hasKeystore) {
            create("braynlabs") {
                storeFile = resolveKeystoreStoreFile(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
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
    implementation("androidx.documentfile:documentfile:1.0.0")

    // In-app player (background playback + lock-screen / notification controls).
    // Media3 ExoPlayer decodes the decrypted FLAC/MP3/OGG/AAC/WAV directly.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    // Apple-Music-style motion helpers.
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0") // spring physics
    implementation("androidx.palette:palette-ktx:1.0.0")               // album-art colour extraction

    // Audio metadata (tag) read/write for FLAC / MP3 / OGG / M4A.
    implementation("net.jthink:jaudiotagger:3.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
