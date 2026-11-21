plugins {
    id("com.android.application") version "9.4.1"
    id("com.indus.veena.extension")
}

android {
    namespace = "com.veena.newpipe"
    compileSdk = 37

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    defaultConfig {
        applicationId = "com.veena.newpipe.plugin"
        minSdk = 24
        targetSdk = 37
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    if (findProject(":extension-contract") != null) {
        compileOnly(project(":extension-contract"))
    } else {
        compileOnly("com.github.IndusAryan.Veena:veena-extension-contract:main-SNAPSHOT")
    }
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("org.mozilla:rhino") {
        version { strictly("1.7.13") }
    }
}
