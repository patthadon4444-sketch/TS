plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gametranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gametranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // OCR ในเครื่อง (ภาษาละติน/อังกฤษ)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // แปลออฟไลน์ EN -> TH (โหลดโมเดลครั้งแรกครั้งเดียว)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
