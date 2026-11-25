plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sami.assistant"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "com.sami.assistant"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0-m2"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // R8 is off: Vosk (JNA) and ML Kit rely on reflection; an unshrunk build is the one that's been tested.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with debug key for personal sideloading only. Replace for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { noCompress += "tflite" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("ai.picovoice:porcupine-android:4.0.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.ai.edge.litert:litert:1.4.2")          // runs the on-device face model
    implementation("com.google.mlkit:face-detection:16.1.7")           // bundled, offline
    implementation("com.google.mlkit:text-recognition:16.0.1")         // bundled, offline OCR for file search
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    // biometric pulls an old fragment that rejects Compose's activity-result request codes
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("com.alphacephei:vosk-android:0.3.75") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

android.testOptions.unitTests.isReturnDefaultValues = true
dependencies { testImplementation("org.json:json:20250517") }
