plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ehrocha.pulsar.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ehrocha.pulsar"  // Must match phone app for pairing
        minSdk = 30                            // Wear OS 3+
        targetSdk = 35
        versionCode = 339
        versionName = "0.328.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    // Wear-specific Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.compose.material:material-icons-extended")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Wearable Data Layer — paired-device state sync + commands
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    // Kotlin coroutine adapters for Play Services Task<T>.await()
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
