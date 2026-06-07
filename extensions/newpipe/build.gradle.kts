plugins {
    id("com.android.application") version "9.1.1"
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
//    compileOnly(project(":extension-contract"))
    compileOnly("com.squareup.okhttp3:okhttp:5.3.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    //implementation("com.github.IndusAryan:Veena:main-SNAPSHOT")
    compileOnly("com.github.IndusAryan.Veena:veena-extension-contract:main-SNAPSHOT")
    implementation("com.github.UpAllNite-Software:NewPipeExtractor:58ed44bd7ab2271d744eb027c74f0a97e7200079")
    implementation("org.mozilla:rhino") {
        version { strictly("1.7.13") }
    }
}
