plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "fr.preludedash.carte"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.preludedash.carte"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Même clé que Prelude Dash : obligatoire pour avoir le droit de lire ses données
    signingConfigs {
        create("perso") {
            storeFile = file("../app/keystore/preludedash.jks")
            storePassword = "preludedash"
            keyAlias = "preludedash"
            keyPassword = "preludedash"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("perso")
        }
        debug {
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDir("../shared/java")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
