plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val chave = System.getenv("KEYSTORE_FILE")

android {
    namespace = "br.com.touchcell.celular"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    flavorDimensions += "alvo"
    productFlavors {
        create("pc") {
            dimension = "alvo"
            applicationId = "br.com.touchcell.celular.pc"
            resValue("string", "app_name", "Touch Cell PC")
            buildConfigField("String", "TIPO", "\"pc\"")
            buildConfigField("String", "APK", "\"TouchCell-Celular-PC.apk\"")
        }
        create("tv") {
            dimension = "alvo"
            applicationId = "br.com.touchcell.celular.tv"
            resValue("string", "app_name", "Touch Cell TV")
            buildConfigField("String", "TIPO", "\"tv\"")
            buildConfigField("String", "APK", "\"TouchCell-Celular-TV.apk\"")
        }
    }

    signingConfigs {
        if (chave != null) {
            create("release") {
                storeFile = file(chave)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = "touchcell"
                keyPassword = System.getenv("KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (chave != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
