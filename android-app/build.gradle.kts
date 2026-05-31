import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 版本号：CI 可通过环境变量从 v* Tag 注入；本地缺省 1.0.0 / 1。
val appVersionName: String = System.getenv("ST_APP_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"
val appVersionCode: Int = System.getenv("ST_APP_VERSION_CODE")?.toIntOrNull() ?: 1

// Release 签名：CI 把 keystore 解码成文件后，通过环境变量传入。
// 缺省（本机 / M0 / PR）回退 debug 签名，保证可构建出可安装的调试 APK。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreFromEnvPath: String? = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "org.sillytavern"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.sillytavern"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // 仅 arm64-v8a
        ndk { abiFilters += "arm64-v8a" }

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            when {
                keystoreFromEnvPath != null -> {
                    storeFile = file(keystoreFromEnvPath)
                    storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                }
                keystorePropsFile.exists() -> {
                    val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    // 是否具备可用的 release keystore（决定 release 包用正式签名还是回退 debug 签名）
    val hasReleaseKeystore = keystoreFromEnvPath != null || keystorePropsFile.exists()

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // M0 / 无 keystore 时回退 debug 签名，仅用于验证流程，不作正式发布。
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ★ 关键：node 必须以真实 .so 文件落到 nativeLibraryDir 才能 exec。
    // 配合 AndroidManifest 的 extractNativeLibs="true"。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.snakeyaml)
    implementation(libs.kotlinx.coroutines.android)
}
