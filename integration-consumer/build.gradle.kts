plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

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
    "starterImplementation"("io.github.orthoepiccrown0:directstream:1.0.0")
    "coreImplementation"("io.github.orthoepiccrown0:directstream-ui:1.0.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
}
