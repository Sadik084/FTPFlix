import java.util.Properties

// Git-ignored local configuration (see local.properties.example); falls back to
// gradle.properties / -P values so CI can inject secrets too.
val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun configValue(name: String, fallback: String = ""): String =
    localConfig.getProperty(name)
        ?: project.findProperty(name)?.toString()
        ?: fallback

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ftpflix"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ftpflix"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "TMDB_API_KEY", "\"${configValue("TMDB_API_KEY")}\"")
        buildConfigField("String", "HDHUB_ADDON_URL", "\"${configValue("HDHUB_ADDON_URL")}\"")
        buildConfigField("String", "GITHUB_OWNER", "\"${configValue("GITHUB_OWNER")}\"")
        buildConfigField("String", "GITHUB_REPO", "\"${configValue("GITHUB_REPO")}\"")
        buildConfigField(
            "String", "GITHUB_RELEASE_API",
            "\"${configValue("GITHUB_RELEASE_API", "https://api.github.com")}\""
        )
        buildConfigField(
            "String", "CIRCLEFTP_API_BASE",
            "\"${configValue("CIRCLEFTP_API_BASE", "http://new.circleftp.net:5000/api")}\""
        )
        buildConfigField(
            "String", "ROARZONE_BASE_URL",
            "\"${configValue("ROARZONE_BASE_URL", "https://play.roarzone.net")}\""
        )
        buildConfigField(
            "String", "HYPER_ADDON_URL",
            "\"${configValue("HYPER_ADDON_URL", "https://hyper.stravo.site/local")}\""
        )
    }

    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                val props = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed when keystore.properties exists (it does); without it the
            // APK would come out unsigned and Android would refuse to install it.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ABI split APKs plus a universal APK for side-loading; GitHub OTA picks the file matching the device ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.adaptive.nav)
    debugImplementation(libs.compose.ui.tooling.preview)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.nav.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui.compose)

    // Software AC-3 / E-AC-3 / DTS / TrueHD decoder extension. ExoPlayer has no
    // built-in decoder for these, and every stream this app resolves is an MKV
    // carrying AC-3 or E-AC-3 - which is why playback was silent. Google does
    // not publish the FFmpeg extension, so we use Jellyfin's prebuilt AAR.
    implementation(libs.jellyfin.media3.ffmpeg)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.shimmer)
    implementation(libs.palette.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.profileinstaller)
}

// Copies split/universal release APKs into an ota/ folder with publish-ready names.
tasks.register("prepareOtaApks") {
    dependsOn("assembleRelease")
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val otaDir = layout.buildDirectory.dir("outputs/apk/release/ota")
    doLast {
        val src = apkDir.get().asFile
        val dst = otaDir.get().asFile
        dst.mkdirs()
        src.listFiles { f -> f.isFile && f.extension == "apk" }?.forEach { apk ->
            val name = when {
                apk.name.contains("arm64-v8a") -> "FTPFlix-arm64-v8a.apk"
                apk.name.contains("armeabi-v7a") -> "FTPFlix-armeabi-v7a.apk"
                apk.name.contains("x86_64") -> "FTPFlix-x86_64.apk"
                else -> "FTPFlix-universal.apk"
            }
            apk.copyTo(File(dst, name), overwrite = true)
        }
    }
}
