plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nila.skin.app"
    compileSdk = 35

    defaultConfig {
        // Its own id, so it installs beside Nila and never touches Nila's data.
        applicationId = "com.nila.skin"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    androidResources { noCompress += listOf("tflite", "task", "bin") }

    packaging {
        // FastRPC opens the Hexagon libraries from the APK's lib directory, so
        // they have to be unpacked on disk (same as Nila).
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += setOf("**/libQnn*.so", "**/libSnpe*.so", "**/libc++_shared.so")
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCY_INFO")
    }
}

dependencies {
    implementation(project(":skin-ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
