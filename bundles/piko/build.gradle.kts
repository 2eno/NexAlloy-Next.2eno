plugins {
    id("nexalloy.bundle")
}

android {
    namespace = "io.github.nexalloy.patches.piko"

    defaultConfig {
        applicationId = "io.github.nexalloy.patches.piko"
        versionCode = 5
        versionName = "1.1.0"
    }
}

nexalloyBundle {
    entry = "io.github.nexalloy.piko.PikoPatches"
    author = "NexAlloy"
    descriptionResource = "bundle_description"
    resourcePackageId = 0x4d
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
