plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// The phone runs the same Python Cast bridge as the Mac app: compose-app/bridge plus the
// mac-spike engine it imports. Copy exactly those sources into assets so they run from real
// files (the bridge reads data files next to its modules) and tests or the Tk app never ship.
val bridgeAssets = layout.buildDirectory.dir("generated/bridgeAssets")
val syncBridgeAssets by tasks.registering(Sync::class) {
    into(bridgeAssets.map { it.dir("bridge") })
    from(rootProject.file("bridge")) { include("*.py", "channels.json"); exclude("test_*.py") }
    from(rootProject.file("../mac-spike")) { include("engine.py", "grouping.py") }
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
        versionCode = 25
        versionName = "0.6.6"
    }
    sourceSets["main"].assets.srcDir(bridgeAssets)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = true; resources.excludes += "META-INF/DEPENDENCIES" }
}
tasks.named("preBuild") { dependsOn(syncBridgeAssets) }

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            // Same pins as mac-spike/requirements.txt. --no-deps keeps pip from fetching the
            // zeroconf sdist, whose optional Cython modules cannot be built for Android; the
            // checked-in wheel is that release with the native modules removed (pure Python).
            options("--no-deps")
            install("wheels/zeroconf-0.151.5-py3-none-any.whl")
            install("ifaddr==0.2.0")
            install("PyChromecast==14.0.10")
            install("casttube==0.2.1")
            install("protobuf==7.36.2")
            install("requests==2.34.2")
            install("urllib3==2.8.0")
            install("idna==3.20")
            install("certifi==2026.7.22")
            install("charset-normalizer==3.5.2")
            // Android build of Pillow; the Mac pins 12.3.0. Only cover previews use it.
            install("pillow==11.0.0")
            install("chaquopy-libjpeg==1.5.3")
            install("chaquopy-freetype==2.9.1")
            install("chaquopy-libpng==1.6.34")
        }
    }
}

kotlin { jvmToolchain(17) }
dependencies {
    // Source resolution stays on the proven on-device yt-dlp runtime (same 2026.08.19 release
    // as the Mac). The Python bridge calls it instead of spawning `python -m yt_dlp`.
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
