plugins { alias(libs.plugins.android.library) }

android {
    publishing { singleVariant("release") { withSourcesJar() } }
    namespace = "com.epiccrown.directstream.starter"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 30 }
}

dependencies {
    api(project(":sdk-ui"))
    api(project(":sdk-firebase"))
}
