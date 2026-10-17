import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
}
kotlin {
    jvm("desktop")
    androidTarget()
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        val desktopMain by getting {
            dependencies { implementation(compose.desktop.currentOs); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2") }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "com.moozaq.shared"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
compose.desktop {
    application {
        mainClass = "com.moozaq.MainKt"
        jvmArgs += "-Dmoozaq.root=${rootProject.projectDir.parentFile.absolutePath}"
        // AWT otherwise defaults to Aqua even though the Compose content is dark.
        // Set before AppKit/AWT initializes, for both `run` and packaged launches.
        jvmArgs += "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Moozaq"
            packageVersion = "1.0.0"
            macOS { iconFile.set(project.file("../../mac-spike/Moozaq Cast Lab.app/Contents/Resources/Moozaq.icns")) }
        }
    }
}
compose.resources { packageOfResClass = "com.moozaq.resources" }

// A separate test-only window: no Python engine, discovery or speaker commands.
tasks.register<JavaExec>("designPreview") {
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
    dependsOn("desktopTestClasses")
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.moozaq.DesignPreviewKt")
    systemProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}
