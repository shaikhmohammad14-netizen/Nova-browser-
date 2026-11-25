plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bump this to update the browser engine (see maven.mozilla.org/?prefix=maven2/org/mozilla/geckoview/)
val geckoVersion = "115.0.20230710165010"

android {
    namespace = "com.example.ucbrowser"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.ucbrowser"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
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
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:$geckoVersion")
}
