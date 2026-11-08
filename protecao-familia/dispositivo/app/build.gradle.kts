plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val chave = System.getenv("KEYSTORE_FILE")

android {
    namespace = "br.com.protecaofamilia.dispositivo"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.protecaofamilia.dispositivo"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    signingConfigs {
        if (chave != null) {
            create("release") {
                storeFile = file(chave)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = "protecaofamilia"
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

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
