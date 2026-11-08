plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chaque build GitHub a un numéro qui augmente : ça permet de mettre à jour
// l'appli par-dessus l'ancienne sans la désinstaller.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "fr.preludedash"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.preludedash"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Clé de signature fixe : toutes les versions sont signées pareil,
    // donc les mises à jour s'installent par-dessus sans conflit.
    signingConfigs {
        create("perso") {
            storeFile = file("keystore/preludedash.jks")
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

    // Code commun avec l'appli Prelude Carte
    sourceSets["main"].java.srcDir("../shared/java")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
