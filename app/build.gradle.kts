plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mesengerhubtel"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.mesengerhubtel"
        minSdk = 24
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

    // نام فایل خروجی APK را هم به MesengerHubTel تغییر می‌دهد (پیش‌فرض گریدل چیزی مثل
    // app-debug.apk است که ربطی به نام پروژه ندارد).
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "MesengerHubTel-${name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

