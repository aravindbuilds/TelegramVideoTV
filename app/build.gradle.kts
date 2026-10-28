import java.util.Properties
import java.security.SecureRandom

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
        versionCode = 6
        versionName = "1.1"
    }

    flavorDimensions += "abi"
    productFlavors {
        // Falls back warmly on every device (arm64/x86 boxes run v7a natively)
        create("v7a") {
            dimension = "abi"
            ndk {
                abiFilters.add("armeabi-v7a")
            }
        }
        // All 4 TDLib ABIs: emulators and true arm64 devices
        create("universal") {
            dimension = "abi"
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

    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/bakedApi"))
        }
    }
}

// ---------------------------------------------------------------------------
// BakedApi: TDLib api_id/api_hash read at build time from an untracked secrets
// file, XOR-obfuscated into a Kotlin object. Absent file -> stub so the project
// still builds. Checked in order:
//   1. <project>/secrets.properties
//   2. <parent>/tgtv-secrets.properties   (shared by TV + phone apps)
// Not a defence against a determined reverse engineer - no client signature is.
// ---------------------------------------------------------------------------
val bakedApiDir = layout.buildDirectory.dir("generated/bakedApi").get().asFile

val generateBakedApi by tasks.registering {
    val secrets = listOf(
        rootProject.file("secrets.properties"),
        rootProject.projectDir.parentFile?.resolve("tgtv-secrets.properties"),
    ).firstOrNull { it?.isFile == true }

    fun prop(name: String): String =
        secrets?.let {
            val p = Properties(); it.inputStream().use { s -> p.load(s) }
            p.getProperty(name)?.trim().orEmpty()
        }.orEmpty()

    val rawId = prop("api_id")
    val rawHash = prop("api_hash")
    outputs.upToDateWhen { false }
    inputs.property("apiDigest", "${rawId.hashCode()}:${rawHash.hashCode()}")
    doLast {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fun obfuscate(s: String): String {
            val src = s.toByteArray(Charsets.UTF_8)
            return src.indices.joinToString(",") { i ->
                "%d".format(src[i].toInt() xor key[i % key.size].toInt())
            }
        }
        val keyBytes = key.joinToString(",") { b -> "%d".format(b.toInt()) }
        val kt = """
            package com.tgtv.videotv.baked

            object BakedApi {
                private val ID = intArrayOf(${obfuscate(rawId)})
                private val HASH = intArrayOf(${obfuscate(rawHash)})
                private val KEY = byteArrayOf($keyBytes)

                private fun decode(src: IntArray): String {
                    val out = ByteArray(src.size)
                    for (i in src.indices) out[i] = (src[i] xor KEY[i % KEY.size].toInt()).toByte()
                    return String(out, Charsets.UTF_8)
                }

                val present: Boolean
                    get() = apiId() > 0 && apiHash().isNotBlank()

                fun apiId(): Int = decode(ID).trim().toIntOrNull() ?: 0

                fun apiHash(): String = decode(HASH)
            }
        """.trimIndent()
        val pkgDir = bakedApiDir.resolve("com/tgtv/videotv/baked")
        pkgDir.mkdirs()
        pkgDir.resolve("BakedApi.kt").writeText(kt, Charsets.UTF_8)
    }
}

tasks.named("preBuild") { dependsOn(generateBakedApi) }

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