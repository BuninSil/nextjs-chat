plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.mlbb.overlay"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "dev.mlbb.overlay"
        minSdk = 26
        targetSdk = 34
        // CI передаёт номер запуска: -PversionCode=N. Он же попадает в тег релиза.
        val ciVersion = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = ciVersion
        versionName = "1.0.$ciVersion"

        // Откуда тянуть обновления (GitHub owner/repo), можно поменять в приложении
        buildConfigField("String", "UPDATE_REPO", "\"BuninSil/nextjs-chat\"")
        buildConfigField("String", "AUTHOR", "\"BuninSil\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Фиксированный debug-ключ в репозитории: APK из разных CI-сборок
        // ставятся поверх друг друга без "INSTALL_FAILED_UPDATE_INCOMPATIBLE".
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // VPN-ядро sing-box (libbox), собрано нашим CI из официальных исходников (ветка libbox)
    implementation(files("libs/libbox.aar"))
    // Shizuku: чтение таблицы сокетов с правами adb-шелла, без своего VPN
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
