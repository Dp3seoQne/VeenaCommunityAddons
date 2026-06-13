plugins {
    id("com.android.application")
    id("com.indus.veena.extension")
}

android {
    namespace = "com.veena.ytmusic"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.veena.ytmusic"
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
    implementation("dev.toastbits:ytm-kt:0.5.1")
}