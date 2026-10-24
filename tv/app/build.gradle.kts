plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val chave = System.getenv("KEYSTORE_FILE")

android {
    namespace = "br.com.touchcell.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.touchcell.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
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
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.version")
        }
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.mobile:dadb:1.2.7")
}
