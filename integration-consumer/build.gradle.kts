import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

val sdkDefaults = Properties().apply { file("../gradle.properties").inputStream().use { load(it) } }
val sdkVersion =
    providers.gradleProperty("sdkVersion").getOrElse(sdkDefaults.getProperty("sdkVersion"))
val sdkGroup = providers.gradleProperty("sdkGroup").getOrElse(sdkDefaults.getProperty("sdkGroup"))

android {
    ndkVersion = "29.0.14033849"
    namespace = "com.epiccrown.directstream.consumer"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.epiccrown.directstream.consumer"
        minSdk = 30
        targetSdk = 37
    }
    flavorDimensions += "integration"
    productFlavors {
        create("starter") { dimension = "integration" }
        create("core") { dimension = "integration" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes { release { optimization { enable = true } } }
}

dependencies {
    "starterImplementation"("$sdkGroup:directstream:$sdkVersion")
    "coreImplementation"("$sdkGroup:directstream-ui:$sdkVersion")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
}
