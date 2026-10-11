plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nila.skin.ai"
    compileSdk = 35
    defaultConfig { minSdk = 27 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("tflite", "task", "bin") }
}

dependencies {
    api(project(":skin-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mediapipe.vision)
    // Qualcomm's runtime: the only route to the Hexagon NPU, same version as Nila.
    implementation(libs.geniex.android)
}
