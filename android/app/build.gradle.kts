plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ttsmc.deepseekchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ttsmc.deepseekchat"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // CI 首次构建时生成并提交 android/app/debug.keystore，
    // 之后每次都用同一把钥匙签名 —— 这样新版能直接覆盖升级，不用卸载重装。
    signingConfigs {
        val ks = file("debug.keystore")
        if (ks.exists()) {
            create("stable") {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            if (file("debug.keystore").exists()) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 未配置签名时也能产出可安装的未签名 APK；正式发布请自行配置 keystore
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true   // AGP 8 默认关闭，MainActivity 里用到了 BuildConfig.DEBUG
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
