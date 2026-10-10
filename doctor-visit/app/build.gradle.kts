plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nila.visit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nila.visit"
        // GenieX declares minSdk 27, the same floor as the main app.
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isReturnDefaultValues = true } }

    androidResources { noCompress += listOf("bin", "onnx") }

    packaging {
        // FastRPC opens the Hexagon libraries from the APK's lib directory, so
        // they have to be extracted rather than page-mapped out of the archive.
        jniLibs.useLegacyPackaging = true
        // GenieX and qnn-runtime both ship the QAIRT 2.45 libraries; they are
        // the same version on purpose (see README), so either copy will do.
        jniLibs.pickFirsts += setOf("**/libQnn*.so", "**/libSnpe*.so", "**/libc++_shared.so")
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    // The chat model, exactly as the main app runs it: Qwen3.5-2B on the NPU.
    implementation("com.qualcomm.qti:geniex-android:0.4.0")

    // Whisper, as AI Hub compiles it: ONNX graphs wrapping QNN context binaries,
    // run by ONNX Runtime's QNN execution provider on the HTP. The QNN runtime
    // is pinned to 2.45 -- the version the v0.62 Whisper bundle was compiled
    // with, and the version GenieX 0.4.0 ships -- so one set of Hexagon
    // libraries serves both models.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0") {
        exclude(group = "com.qualcomm.qti", module = "qnn-runtime")
    }
    implementation("com.qualcomm.qti:qnn-runtime:2.45.0")

    // FFT for the mel spectrogram (n_fft = 400, which is not a power of two).
    implementation("com.github.wendykierp:JTransforms:3.1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("com.github.wendykierp:JTransforms:3.1")
}
