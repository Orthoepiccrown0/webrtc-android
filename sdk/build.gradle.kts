plugins { alias(libs.plugins.android.library) }

android {
    publishing { singleVariant("release") { withSourcesJar() } }
    namespace = "com.epiccrown.directstream"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 30
        buildConfigField(
            "String",
            "SDK_VERSION",
            "\"${providers.gradleProperty("sdkVersion").get()}\"",
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk-api"))
    implementation(libs.webrtc)
    implementation(libs.timber)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.core:core-telecom:1.0.0")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(libs.androidx.junit)
}
