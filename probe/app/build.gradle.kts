plugins {
    id("com.android.application")
}
android {
    namespace = "com.lonerxone.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.lonerxone.probe"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
