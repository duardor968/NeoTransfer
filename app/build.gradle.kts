import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Private signing material stays outside the repository and the APK.
val localSigningFile = File(System.getProperty("user.home"), ".neotransfer/signing.properties")
val localSigning = Properties().apply { if (localSigningFile.isFile) localSigningFile.inputStream().use(::load) }

android {
    namespace = "dev.duardo.neotransfer"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.duardo.neotransfer"
        minSdk = 35
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "dev.duardo.neotransfer.BankingChecks"
    }

    buildFeatures { compose = true }
    signingConfigs {
        if (localSigningFile.isFile) create("localRelease") {
            storeFile = file(requireNotNull(localSigning.getProperty("storeFile")))
            storePassword = requireNotNull(localSigning.getProperty("storePassword"))
            keyAlias = requireNotNull(localSigning.getProperty("keyAlias"))
            keyPassword = requireNotNull(localSigning.getProperty("keyPassword"))
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("localRelease")
        }
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            matchingFallbacks += "debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.zxing:core:3.5.3")
}
