pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        if (
            providers
                .gradleProperty("useLocalSdkRepository")
                .map(String::toBoolean)
                .getOrElse(false)
        ) {
            maven {
                url = uri("../build/sdk-maven")
                content { includeGroup("io.github.orthoepiccrown0") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "DirectStreamExternalConsumer"
