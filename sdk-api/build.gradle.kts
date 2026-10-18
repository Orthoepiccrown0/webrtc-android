plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

android {
    publishing { singleVariant("release") { withSourcesJar() } }
    namespace = "com.epiccrown.directstream.api"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Public @Serializable models expose KSerializer in their generated API.
    api(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
