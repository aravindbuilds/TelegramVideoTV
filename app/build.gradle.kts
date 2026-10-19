plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tgtv.videotv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tgtv.videotv"
        // Keep 26: TDLib (io.github.tdlib-android) declares minSdk 26; MiBox4 ships Android 9
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // MiBox4 only runs armeabi-v7a; dropping the other TDLib ABIs
        // shrinks the APK (and install footprint) by ~75%.
        ndk {
            abiFilters.add("armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    // Prebuilt TDLib (org.drinkless.tdlib.*) with native libs for all Android ABIs
    implementation("io.github.tdlib-android:core:0.1.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Android TV Leanback UI
    implementation("androidx.leanback:leanback:1.0.0")
    // QR code generation for Telegram device-link login
    implementation("com.google.zxing:core:3.5.3")
    // Tiny embedded HTTP server: lets the phone type credentials over the LAN
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Media3 / ExoPlayer for the streaming player
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
