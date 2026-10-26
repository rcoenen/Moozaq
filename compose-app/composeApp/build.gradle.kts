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
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.9.1")
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

val macAudioPlayerSource = layout.projectDirectory.file("src/desktopMain/swift/MoozaqAudioPlayer.swift")
val macAudioPlayerBinary = layout.buildDirectory.file("macos/MoozaqAudioPlayer")
val macAppBundle = layout.buildDirectory.dir("compose/binaries/main/app/Moozaq.app")
val macAppEntitlements = layout.projectDirectory.file("macos/Moozaq.entitlements")
val compileMacAudioPlayer by tasks.registering(Exec::class) {
    inputs.file(macAudioPlayerSource)
    outputs.file(macAudioPlayerBinary)
    executable = "/usr/bin/swiftc"
    args("-parse-as-library", "-swift-version", "5", "-warnings-as-errors",
        macAudioPlayerSource.asFile.absolutePath, "-o", macAudioPlayerBinary.get().asFile.absolutePath)
    doFirst { macAudioPlayerBinary.get().asFile.parentFile.mkdirs() }
}
tasks.matching { it.name == "run" }.configureEach { dependsOn(compileMacAudioPlayer) }
tasks.named("desktopTest") { dependsOn(compileMacAudioPlayer) }
val signMacApp by tasks.registering(Exec::class) {
    dependsOn(compileMacAudioPlayer, "createDistributable")
    inputs.file(macAppEntitlements)
    commandLine("/usr/bin/codesign", "--force", "--deep", "--sign", "-", "--options", "runtime",
        "--entitlements", macAppEntitlements.asFile.absolutePath, macAppBundle.get().asFile.absolutePath)
}
tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(compileMacAudioPlayer)
    inputs.file(macAudioPlayerBinary)
    doLast {
        val helper = macAppBundle.get().file("Contents/Resources/MoozaqAudioPlayer").asFile
        helper.parentFile.mkdirs()
        macAudioPlayerBinary.get().asFile.copyTo(helper, overwrite = true)
        helper.setExecutable(true)
    }
    finalizedBy(signMacApp)
}

// A separate test-only window: no Python engine, discovery or speaker commands.
tasks.register<JavaExec>("designPreview") {
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
    dependsOn("desktopTestClasses")
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.moozaq.DesignPreviewKt")
    systemProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

tasks.register<JavaExec>("catalogAcceptance") {
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
    dependsOn("desktopTestClasses")
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.moozaq.CatalogAcceptanceKt")
    systemProperty("moozaq.root", rootProject.projectDir.parentFile.absolutePath)
}
