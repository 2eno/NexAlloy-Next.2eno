import java.util.Properties

plugins {
    id("nexalloy.bundle")
}

android {
    namespace = "io.github.nexalloy.patches2eno"

    defaultConfig {
        applicationId = "io.github.nexalloy.patches2eno"
        // Follows the version of the 2eno Patches, whose extension code this bundle compiles.
        val patchesVersion = Properties().apply {
            rootProject.file("2eno-patches/gradle.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }["version"] as String? ?: "0.0.0"
        // Bumped for changes to the hooks of this bundle, reset when the 2eno Patches version changes.
        val bundleRevision = 10
        versionName = "$patchesVersion-$bundleRevision"
        versionCode = patchesVersion.split('.').fold(0) { code, part -> code * 1000 + (part.toIntOrNull() ?: 0) } * 100 + bundleRevision
    }
    sourceSets {
        named("main") {
            val srcDirs = arrayOf("../../2eno-patches/extensions/twoeno/src/main/java")
            java.directories += srcDirs
            kotlin.directories += srcDirs
        }
    }
}

nexalloyBundle {
    entry = "io.github.nexalloy.2eno.Patches2eno"
    author = "2eno"
    descriptionResource = "bundle_description"
    resourcePackageId = 0x4c
}

dependencies {
    compileOnly(libs.nexalloy.patcher)
}

// Fingerprint tests run against the APKs in `binaries/`, named `<package name>…apk`.
android.sourceSets.named("test") {
    java.directories += "../testing/java"
    kotlin.directories += "../testing/java"
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.library.path", file("../testing/jniLibs").path)
}
dependencies {
    testImplementation(libs.nexalloy.patcher)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.jadx.core)
    testImplementation(libs.slf4j.simple)
}
