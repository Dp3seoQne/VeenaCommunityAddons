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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    val contractDir = file(providers.gradleProperty("veenaContractDir").getOrElse("../extension-contract"))
    if (contractDir.exists()) {
        versionCatalogs { create("libs") { from(files(contractDir.resolve("../gradle/libs.versions.toml"))) } }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.syk.sh/releases")
        // Release versions
        maven("https://maven.syk.sh")

        // Development snapshot versions
        maven("https://maven.syk.sh/snapshots")
        //mavenLocal()
    }
}

rootProject.name = "VeenaExtensions"

include(":extensions:newpipe")
include(":extensions:soundcloud")
include(":extensions:saavn")
include("extensions:ytmkt")

// Use the colocated contract when developing both repositories together.
// Standalone CI can pass -PveenaContractDir=/path/to/Veena/extension-contract.
val localContract = file(providers.gradleProperty("veenaContractDir").getOrElse("../extension-contract"))
if (localContract.exists()) {
    include(":extension-contract")
    project(":extension-contract").projectDir = localContract
}
