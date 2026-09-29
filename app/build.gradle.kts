plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mlaty.cribbage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mlaty.cribbage"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Подписано отладочным ключом, чтобы APK из CI ставился на телефон без доп. настроек.
            // Свою подпись можно задать позже, см. README.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Внешних зависимостей нет: только Kotlin stdlib.
    // Это держит APK ~500 КБ и убирает самый частый источник падения сборки.
}

dependencies {
}
