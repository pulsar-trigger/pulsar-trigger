import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.ehrocha.pulsar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ehrocha.pulsar"
        minSdk = 26
        targetSdk = 35
        versionCode = 92
        versionName = "0.81.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "../pulsar-release.jks"))
            storePassword = keystoreProperties.getProperty("storePassword", System.getenv("KEYSTORE_PASSWORD") ?: "")
            keyAlias = keystoreProperties.getProperty("keyAlias", System.getenv("KEY_ALIAS") ?: "")
            keyPassword = keystoreProperties.getProperty("keyPassword", System.getenv("KEY_PASSWORD") ?: "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // BLE
    implementation("no.nordicsemi.android:ble:2.7.5")
    implementation("no.nordicsemi.android:ble-ktx:2.7.5")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // WorkManager — periodic background update checks
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // MapLibre — map-based location picker
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.json:json:20231013")
}
