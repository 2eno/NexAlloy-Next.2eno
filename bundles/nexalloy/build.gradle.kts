import com.google.protobuf.gradle.proto
import groovy.xml.MarkupBuilder
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.NodeChild
import org.gradle.kotlin.dsl.support.uppercaseFirstChar
import java.util.Properties

plugins {
    id("nexalloy.bundle")
    alias(libs.plugins.protobuf)
}

android {
    namespace = "io.github.nexalloy.patches"

    defaultConfig {
        applicationId = "io.github.nexalloy.patches"
        versionCode = 4
        val patchVersion = Properties().apply {
            rootProject.file("morphe-patches/gradle.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }["version"]
        versionName = "1.0.$versionCode"
        buildConfigField("String", "PATCH_VERSION", "\"$patchVersion\"")
    }
    sourceSets {
        named("main") {
            val srcDirs = arrayOf(
                "../../morphe-patches/extensions/shared/library/src/main/java",
                "../../morphe-patches/extensions/shared-youtube/library/src/main/java",
                "../../morphe-patches/extensions/youtube/src/main/java",
                "../../morphe-patches/extensions/music/src/main/java",
                "../../morphe-patches/extensions/reddit/src/main/java",
                "../../morphe-patches-library/extension-library/src/main/java",
            )
            java.directories += srcDirs
            kotlin.directories += srcDirs

            proto {
                srcDirs(
                    "../../morphe-patches/extensions/youtube/src/main/proto",
                    "../../morphe-patches/extensions/shared-youtube/library/src/main/proto",
                )
            }
        }
    }
}

nexalloyBundle {
    entry = "io.github.nexalloy.patches.NexAlloyPatches"
    author = "NexAlloy"
    descriptionResource = "bundle_description"
    resourcePackageId = 0x4b
}

// Exclude Morphe-specific files that depend on protobuf/innertube/javascriptengine
// which are not available in the Xposed module build context.
tasks.withType<JavaCompile>().configureEach {
    exclude(
        "**/patches/HideRelatedVideosPatch.java",
        "**/patches/playback/quality/PrioritizeVideoQualityPatch.java",
        "**/OAuth2Preference.java",
        "**/SpoofVideoStreamsSignInPreference.java",
        "**/SpoofSignaturePatch.java",
    )
}

dependencies {
    compileOnly(libs.nexalloy.patcher)
    compileOnly(project(":stub"))
    implementation(libs.annotation)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.protobuf.javalite)
    implementation(libs.collections4)
    implementation(libs.lang3)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

/**
 * Generates `MorpheTargets` from the app versions the Morphe patches in the submodule are tested with
 * (the `targets` of their `Compatibility` constants), so the recommended versions follow the submodule.
 */
abstract class GenerateMorpheTargetsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val constants: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val fields = constants.files.sortedBy { it.path }.joinToString("\n") { file ->
            val name = file.parentFile.parentFile.name.uppercase()
            val targets = Regex("""AppTarget\((.*?)\)""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
                .mapNotNull { match ->
                    val version = Regex("""version\s*=\s*"([^"]+)"""").find(match.groupValues[1])?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    val experimental = "isExperimental = true" in match.groupValues[1]
                    "new AppTarget(\"$version\", $experimental)"
                }.toList()
            "    public static final List<AppTarget> $name = Arrays.asList(${targets.joinToString(", ")});"
        }
        val dir = outputDirectory.get().asFile.resolve("io/github/nexalloy/patches").apply { mkdirs() }
        dir.resolve("MorpheTargets.java").writeText(
            """
            |package io.github.nexalloy.patches;
            |
            |import io.github.nexalloy.AppTarget;
            |import java.util.Arrays;
            |import java.util.List;
            |
            |/** App versions the Morphe patches are tested with, newest first. Generated from the morphe-patches submodule. */
            |public final class MorpheTargets {
            |$fields
            |
            |    private MorpheTargets() {
            |    }
            |}
            |""".trimMargin()
        )
    }
}

abstract class GenerateStringsTask @Inject constructor(
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private fun writeNode(builder: MarkupBuilder, node: Any?) {
        if (node !is NodeChild) return
        val attributes = node.attributes()
        builder.withGroovyBuilder {
            if (node.children().any()) {
                node.name()(attributes) {
                    node.children().forEach {
                        writeNode(builder, it)
                    }
                }
            } else {
                node.name()(attributes, node.text())
            }
        }
    }

    /**
     * Morphe addresources structure:
     *   values/youtube/strings.xml, values/shared/strings.xml, etc.
     * Each XML is flat: <resources> <string name="...">...</string> ... </resources>
     *
     * Merge all subdirectory XMLs into a single output file per variant.
     */
    private fun mergeResources(inputFiles: List<File>, output: File) {
        output.parentFile.mkdirs()
        output.writer().use { writer ->
            val builder = MarkupBuilder(writer)
            builder.doubleQuotes = true
            builder.withGroovyBuilder {
                val keys = mutableSetOf<String>()
                "resources" {
                    for (inputFile in inputFiles) {
                        if (!inputFile.exists()) continue
                        val inputXml = XmlSlurper().parse(inputFile)
                        // Flat structure: direct children of <resources>
                        inputXml.children().forEach {
                            if (it !is NodeChild) return@forEach
                            val key = it.attributes()["name"] as? String ?: return@forEach
                            if (keys.contains(key)) return@forEach
                            writeNode(builder, it)
                            keys.add(key)
                        }
                    }
                }
            }
        }
    }

    // Subdirectories within each variant that contain resource files.
    private val subDirs = listOf("shared", "shared-youtube", "youtube", "music", "reddit", "sponsorblock")

    @TaskAction
    fun action() {
        val inputDir = inputDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile

        runCatching {
            // Process each variant directory (values, values-xx-rYY, ...)
            inputDir.listFiles()?.filter { it.isDirectory }?.forEach { variant ->
                val genResDir = File(outputDir, variant.name).apply { mkdirs() }

                // Merge strings.xml from all subdirectories
                val stringFiles = subDirs.map { File(variant, "$it/strings.xml") }
                mergeResources(stringFiles, File(genResDir, "strings.xml"))

                // Merge arrays.xml from all subdirectories
                val arrayFiles = subDirs.map { File(variant, "$it/arrays.xml") }
                if (arrayFiles.any { it.exists() }) {
                    mergeResources(arrayFiles, File(genResDir, "arrays.xml"))
                }
            }
        }.onFailure {
            System.err.println(it)
            throw it
        }
    }
}

abstract class CopyResourcesTask @Inject constructor() : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun action() {
        val baseDir = inputDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()

        val resourcePaths = mapOf(
            "qualitybutton/drawable" to null,
            "settings/drawable" to null,
            "settings/menu" to null,
            "settings/layout" to listOf("morphe_settings_with_toolbar.xml"),
            "sponsorblock/drawable" to null,
            "sponsorblock/layout" to listOf("morphe_sb_skip_sponsor_button.xml"),
            "swipecontrols/drawable" to null,
            "copyvideolinkbutton/drawable" to null,
            "downloads/drawable" to null,
            "speedbutton/drawable" to null,
            "navigationbuttons/drawable" to null,
            "speed/drawable" to null,
        )

        for ((resourcePath, excludes) in resourcePaths) {
            val dir = resourcePath.substringAfter('/')
            val sourceDir = File(baseDir, resourcePath)
            val targetDir = File(outputDir, dir)
            sourceDir.listFiles()?.forEach { file ->
                if (excludes == null || !excludes.contains(file.name)) {
                    file.copyTo(File(targetDir, file.name), overwrite = true)
                }
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.uppercaseFirstChar()
        val targetsTask = project.tasks.register<GenerateMorpheTargetsTask>("generateMorpheTargets$variantName") {
            constants.from(
                listOf("youtube", "music", "reddit").map {
                    project.file("../../morphe-patches/patches/src/main/kotlin/app/morphe/patches/$it/shared/Constants.kt")
                }
            )
        }
        variant.sources.java?.addGeneratedSourceDirectory(targetsTask, GenerateMorpheTargetsTask::outputDirectory)
        val strTask = project.tasks.register<GenerateStringsTask>("generateStrings$variantName") {
            inputDirectory.set(project.file("../../morphe-patches/patches/src/main/resources/addresources"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            strTask, GenerateStringsTask::outputDirectory
        )

        val resTask = project.tasks.register<CopyResourcesTask>("copyResources$variantName") {
            inputDirectory.set(project.file("../../morphe-patches/patches/src/main/resources"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            resTask, CopyResourcesTask::outputDirectory
        )
    }
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
