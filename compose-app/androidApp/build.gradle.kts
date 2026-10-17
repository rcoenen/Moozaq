plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.moozaq.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.moozaq.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 9
        versionName = "0.3.7-standalone"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    packaging { jniLibs.useLegacyPackaging = true; resources.excludes += "META-INF/DEPENDENCIES" }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("androidx.mediarouter:mediarouter:1.8.0-beta01")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    implementation(project(":composeApp"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.compose.material3:material3:1.3.2")
}
