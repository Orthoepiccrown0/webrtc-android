plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

apply(from = rootProject.file("gradle/sample-configuration.gradle.kts"))

apply(from = rootProject.file("gradle/release-signing.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val sampleSettings = extra["directStreamSampleSettings"] as Map<String, String>

@Suppress("UNCHECKED_CAST")
val releaseSigning = extra["directStreamReleaseSigning"] as Map<String, String>

fun config(name: String) = sampleSettings.getValue(name)

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Published SDK by default; opt in to project modules only while developing the SDK.
val showcaseUseLocalSdk =
    providers.gradleProperty("showcaseUseLocalSdk").map(String::toBoolean).getOrElse(false)
val sdkGroup = providers.gradleProperty("sdkGroup").get()
val sdkVersion = providers.gradleProperty("sdkVersion").get()

android {
    ndkVersion = libs.versions.ndk.get()
    namespace = "com.epiccrown.directstream.showcase"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.epiccrown.webrtctest"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            providers
                .gradleProperty("directStreamTestRunner")
                .getOrElse("androidx.test.runner.AndroidJUnitRunner")
        listOf("PROJECT_ID", "APPLICATION_ID", "API_KEY", "DATABASE_URL", "SENDER_ID", "WORKER_URL")
            .forEach {
                buildConfigField("String", it, quoted(config(it)))
            }
        buildConfigField("boolean", "FORCE_RELAY", config("FORCE_RELAY").ifBlank { "false" })
        resValue("string", "google_app_id", config("APPLICATION_ID"))
        resValue("string", "google_api_key", config("API_KEY"))
        resValue("string", "project_id", config("PROJECT_ID"))
        resValue("string", "firebase_database_url", config("DATABASE_URL"))
        resValue("string", "gcm_defaultSenderId", config("SENDER_ID"))
    }

    signingConfigs {
        if (releaseSigning.isNotEmpty()) {
            create("localRelease") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            if (releaseSigning.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("localRelease")
            }
            isDebuggable = false
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        resValues = true
        compose = true
    }
}

dependencies {
    if (showcaseUseLocalSdk) {
        implementation(project(":sdk-starter"))
    } else {
        implementation("$sdkGroup:directstream:$sdkVersion")
    }
    implementation(libs.coroutines.tasks)
    implementation(libs.timber)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.firebase.messaging)
    implementation(platform(libs.firebase.bom))
    androidTestImplementation(platform(libs.firebase.bom))
    androidTestImplementation(libs.firebase.auth)
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(libs.webrtc)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:rules:1.7.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
