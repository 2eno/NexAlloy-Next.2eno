/*
 * Builds a NexAlloy patch bundle: an APK whose code and resources NexAlloy loads into patched apps.
 *
 * Configure it with the `nexalloyBundle` extension and the bundle manifest (see bundles/README.md).
 * `./gradlew <module>:assembleBundle` writes `build/outputs/bundle/<module>-<version>.nxb`.
 */

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.android.application")
}

val bundleConfig: NexAlloyBundleExtension = extensions.create<NexAlloyBundleExtension>("nexalloyBundle")
bundleConfig.author.convention("")
bundleConfig.descriptionResource.convention("")

val patcherApiVersion = providers.gradleProperty("patcherApiVersion").orElse("1")

configure<ApplicationExtension> {
    defaultConfig {
        manifestPlaceholders["nexalloyPatcherApiVersion"] = patcherApiVersion.get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures.buildConfig = true
    buildTypes {
        release {
            // NexAlloy shares its patcher API and the Kotlin stdlib with bundles; nothing may be renamed.
            isMinifyEnabled = false
            // Bundles need not be signed by anyone in particular, but a signed APK is a valid APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging.resources.excludes += listOf("META-INF/**", "kotlin/**", "**.bin")
    lint.checkReleaseBuilds = false
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
            "-Xno-call-assertions",
            "-Xcontext-parameters"
        )
        jvmTarget = JvmTarget.JVM_17
    }
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    finalizeDsl { android ->
        android.androidResources.additionalParameters += listOf(
            "--allow-reserved-package-id", "--package-id", "0x%02x".format(bundleConfig.resourcePackageId.get())
        )
    }
    onVariants { variant ->
        variant.manifestPlaceholders.put("nexalloyBundleEntry", bundleConfig.entry)
        variant.manifestPlaceholders.put("nexalloyBundleAuthor", bundleConfig.author)
        variant.manifestPlaceholders.put(
            "nexalloyBundleDescription",
            bundleConfig.descriptionResource.map { if (it.isEmpty()) "" else "@string/$it" }
        )
    }
}

// Classes NexAlloy provides at runtime must not be packaged, or the bundle would carry dead copies.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") && "Test" !in name) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "androidx.annotation")
    }
}

val assembleBundle = tasks.register<Copy>("assembleBundle") {
    group = "build"
    description = "Builds the release patch bundle (.nxb)."
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(layout.buildDirectory.dir("outputs/bundle"))
}

afterEvaluate {
    val fileName = "${project.name}-${extensions.getByType<ApplicationExtension>().defaultConfig.versionName}.nxb"
    assembleBundle.configure { rename { fileName } }
}
