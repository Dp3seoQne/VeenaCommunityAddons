pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.indus.veena.extension") {
                useModule("com.github.IndusAryan.Veena:com.indus.veena.extension.gradle.plugin:main-SNAPSHOT")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        //mavenLocal()
    }
}

rootProject.name = "VeenaExtensions"

include(":extensions:newpipe")
include(":extensions:soundcloud")
