import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
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
        versionCode = 3
        versionName = "1.0.0-dev.1"
        testInstrumentationRunner = "dev.duardo.neotransfer.BankingChecks"
    }

    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    signingConfigs {
        if (localSigningFile.isFile) create("localRelease") {
            storeFile = file(requireNotNull(localSigning.getProperty("storeFile")))
            storePassword = requireNotNull(localSigning.getProperty("storePassword"))
            keyAlias = requireNotNull(localSigning.getProperty("keyAlias"))
            keyPassword = requireNotNull(localSigning.getProperty("keyPassword"))
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
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

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20260814")
}
