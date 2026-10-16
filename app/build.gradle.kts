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
        versionCode = 101
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Android 15+ phones are 64-bit ARM; the ML Kit and CameraX natives ship for that ABI only.
        ndk { abiFilters += "arm64-v8a" }
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
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // Fakes of the modem, inbox, vault and store shared by JVM and device tests.
    sourceSets["test"].kotlin.srcDir("src/sharedTest/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/sharedTest/kotlin")
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("screens", providers.gradleProperty("screens").getOrElse(""))
                it.maxHeapSize = "3g"
                // Local-only import of a private Transfermóvil backup; absent on other machines.
                System.getenv("NEOTRANSFER_TRM")?.let { v -> it.environment("NEOTRANSFER_TRM", v) }
            }
        }
    }
}

/** Writes res/xml/shortcuts.xml from its template: a static shortcut must name its package, which differs per variant. */
abstract class ShortcutsResource : DefaultTask() {
    @get:InputFile abstract val template: RegularFileProperty
    @get:Input abstract val applicationId: Property<String>
    @get:OutputDirectory abstract val resources: DirectoryProperty

    @TaskAction fun write() {
        val xml = resources.file("xml/shortcuts.xml").get().asFile
        xml.parentFile.mkdirs()
        xml.writeText(template.get().asFile.readText().replace("\${applicationId}", applicationId.get()))
    }
}

androidComponents {
    onVariants { variant ->
        val shortcuts = tasks.register<ShortcutsResource>("generate${variant.name.replaceFirstChar(Char::uppercase)}Shortcuts") {
            template.set(layout.projectDirectory.file("src/main/shortcuts.xml"))
            applicationId.set(variant.applicationId)
        }
        variant.sources.res?.addGeneratedSourceDirectory(shortcuts, ShortcutsResource::resources)
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(project(":designsystem"))
    implementation(project(":scanner"))
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("com.google.zxing:core:3.5.3")
    "previewImplementation"("androidx.compose.ui:ui-test-manifest")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
