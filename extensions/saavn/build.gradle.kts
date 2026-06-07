plugins {
    id("com.android.application")
    id("com.indus.veena.extension")
    kotlin("plugin.serialization") version "2.3.21"
}

android {
    namespace = "com.veena.saavn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.veena.saavn"
        minSdk = 24
        targetSdk = 37
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    compileOnly("com.squareup.okhttp3:okhttp:5.3.2")
    compileOnly("com.github.IndusAryan.Veena:veena-extension-contract:main-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}