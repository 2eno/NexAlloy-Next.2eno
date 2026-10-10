plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    // The Android Gradle Plugin brings the Kotlin Gradle plugin at runtime.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.10")
}
