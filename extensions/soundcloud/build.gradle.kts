plugins {
    id("com.android.application")
    id("com.indus.veena.extension")
}

android {
    namespace = "com.veena.soundcloudplugin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.veena.soundcloud"
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
    //implementation("com.github.IndusAryan:Veena:main-SNAPSHOT")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.2")
}