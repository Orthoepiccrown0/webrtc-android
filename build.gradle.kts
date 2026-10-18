import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.maven.MavenPublication

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.maven.publish.base) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Library builds must never pull either runnable host into the SDK dependency graph.
abstract class VerifySdkBoundaries : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraryBuildFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val allowed = setOf(":sdk-api", ":sdk", ":sdk-ui", ":sdk-firebase", ":sdk-starter")
        val projectReference = Regex("project\\(\"([^\"]+)\"\\)")
        libraryBuildFiles.files.forEach { buildFile ->
            projectReference.findAll(buildFile.readText()).forEach { match ->
                check(match.groupValues[1] in allowed) {
                    "SDK dependency ${match.groupValues[1]} in ${buildFile.path} crosses into a host module"
                }
            }
        }
        logger.lifecycle("SDK project dependencies contain no application/showcase modules.")
    }
}

tasks.register<VerifySdkBoundaries>("verifySdkBoundaries") {
    group = "verification"
    description = "Rejects SDK project dependencies on application or showcase modules."
    libraryBuildFiles.from(
        listOf(
                "sdk",
                "sdk-ui",
                "sdk-firebase",
                "sdk-api",
                "sdk-starter",
            )
            .map { layout.projectDirectory.file("$it/build.gradle.kts") },
    )
}

// One root formatter covers owned sources in every module, including test and preview code.
spotless {
    kotlin {
        target(
            "showcase/src/**/*.kt",
            "sdk*/src/**/*.kt",
            "integration-consumer/src/**/*.kt",
        )
        ktfmt("0.65").kotlinlangStyle()
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "gradle/*.gradle.kts",
            "showcase/build.gradle.kts",
            "sdk*/build.gradle.kts",
            "integration-consumer/*.gradle.kts",
        )
        ktfmt("0.65").kotlinlangStyle()
    }
}

tasks.register("publishSdkLocally") {
    group = "publishing"
    description =
        "Checks and publishes the five SDK artifacts and their sources to build/sdk-maven."
    dependsOn("verifySdkBoundaries", "sdkApiCheck")
    dependsOn(
        listOf("sdk-api", "sdk", "sdk-ui", "sdk-firebase", "sdk-starter").map {
            ":$it:publishReleasePublicationToSdkLocalRepository"
        },
    )
}

tasks.register<Exec>("sdkApiDump") {
    group = "verification"
    description = "Explicitly updates the reviewed public JVM ABI baseline."
    dependsOn(
        listOf("sdk-api", "sdk", "sdk-ui", "sdk-firebase", "sdk-starter").map {
            ":$it:bundleReleaseAar"
        },
    )
    environment("JAVA_HOME", System.getProperty("java.home"))
    commandLine("python3", "scripts/sdk_api.py", "--update")
}

tasks.register<Exec>("sdkApiCheck") {
    group = "verification"
    description =
        "Checks public JVM declaration compatibility and rejects native/internal type leaks."
    dependsOn(
        listOf("sdk-api", "sdk", "sdk-ui", "sdk-firebase", "sdk-starter").map {
            ":$it:bundleReleaseAar"
        },
    )
    environment("JAVA_HOME", System.getProperty("java.home"))
    commandLine("python3", "scripts/sdk_api.py")
}

tasks.register("sdkDependencyInventory") {
    group = "verification"
    description = "Lists resolved third-party release dependencies for license review."
    notCompatibleWithConfigurationCache("Inspects release resolution across SDK projects.")
    doLast {
        val rows = sortedSetOf<String>()
        listOf("sdk-api", "sdk", "sdk-ui", "sdk-firebase", "sdk-starter").forEach { module ->
            project(":$module")
                .configurations
                .getByName("releaseRuntimeClasspath")
                .incoming
                .resolutionResult
                .allComponents
                .forEach { component ->
                    if (
                        component.id is org.gradle.api.artifacts.component.ModuleComponentIdentifier
                    ) {
                        component.moduleVersion?.let {
                            rows.add("${it.group}:${it.name}:${it.version}")
                        }
                    }
                }
        }
        val output = layout.buildDirectory.file("reports/sdk-dependencies.txt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(rows.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Resolved SDK inventory: ${output.path}")
    }
}

// Direct module publication must honor the same API and boundary gates as the aggregate task.
subprojects {
    plugins.withId("maven-publish") {
        tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
            dependsOn(rootProject.tasks.named("verifySdkBoundaries"))
            dependsOn(rootProject.tasks.named("sdkApiCheck"))
        }
    }
}

// Run after building the standalone consumer to inspect its packaged release artifacts as well.
tasks.register<Exec>("verifySdkPublication") {
    group = "verification"
    description = "Verifies local Maven metadata and all four standalone consumer APKs."
    dependsOn("publishSdkLocally")
    environment("JAVA_HOME", System.getProperty("java.home"))
    environment("SDK_VERSION", providers.gradleProperty("sdkVersion").get())
    commandLine(
        listOf("python3", "scripts/verify_sdk_publication.py") +
            if (
                providers
                    .gradleProperty("sdkCentralRelease")
                    .map(String::toBoolean)
                    .getOrElse(false)
            ) {
                listOf("--signed")
            } else {
                emptyList()
            },
    )
}

abstract class ValidateSdkReleaseInputs : DefaultTask() {
    @get:Input abstract val centralRelease: Property<Boolean>
    @get:Input abstract val signingKeyPresent: Property<Boolean>
    @get:Input abstract val usernamePresent: Property<Boolean>
    @get:Input abstract val passwordPresent: Property<Boolean>
    @get:Input abstract val requirePortalToken: Property<Boolean>
    @get:InputFile abstract val licenseFile: RegularFileProperty

    @TaskAction
    fun validate() {
        check(centralRelease.get()) { "Signed release tasks require -PsdkCentralRelease=true." }
        check(signingKeyPresent.get()) {
            "Set signingInMemoryKey in private Gradle user properties or ORG_GRADLE_PROJECT_signingInMemoryKey."
        }
        if (requirePortalToken.get()) {
            check(usernamePresent.get() && passwordPresent.get()) {
                "Set mavenCentralUsername and mavenCentralPassword using a Central Portal user token; keep them outside the repository."
            }
        }
        check(licenseFile.get().asFile.isFile) { "The SDK LICENSE file is required." }
        logger.lifecycle(
            "SDK release prerequisites are configured; credential values are not logged.",
        )
    }
}

listOf("validateSdkSigning", "validateCentralRelease").forEach { taskName ->
    tasks.register<ValidateSdkReleaseInputs>(taskName) {
        group = "verification"
        centralRelease.set(
            providers.gradleProperty("sdkCentralRelease").map(String::toBoolean).orElse(false),
        )
        signingKeyPresent.set(
            providers.gradleProperty("signingInMemoryKey").map { it.isNotBlank() }.orElse(false),
        )
        usernamePresent.set(
            providers.gradleProperty("mavenCentralUsername").map { it.isNotBlank() }.orElse(false),
        )
        passwordPresent.set(
            providers.gradleProperty("mavenCentralPassword").map { it.isNotBlank() }.orElse(false),
        )
        requirePortalToken.set(taskName == "validateCentralRelease")
        licenseFile.set(layout.projectDirectory.file("LICENSE"))
    }
}

tasks.register("prepareSdkCentralRelease") {
    group = "publishing"
    description = "Builds signed local SDK publications without uploading to Maven Central."
    dependsOn("validateSdkSigning", "publishSdkLocally")
}

tasks.register("publishSdkToMavenCentral") {
    group = "publishing"
    description =
        "Uploads all five signed SDK modules; sdkAutomaticRelease opts into automatic release."
    dependsOn("validateCentralRelease", "sdkApiCheck", "verifySdkBoundaries")
    dependsOn(
        listOf("sdk-api", "sdk", "sdk-ui", "sdk-firebase", "sdk-starter").map {
            ":$it:publishToMavenCentral"
        },
    )
}

abstract class GenerateSdkDocumentation : DefaultTask() {
    @get:Input abstract val coordinate: Property<String>
    @get:InputFile abstract val baseline: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile.apply { mkdirs() }
        val signature = baseline.get().asFile.readText()
        val escaped = signature.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        directory
            .resolve("index.html")
            .writeText(
                """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <title>${coordinate.get()} documentation</title></head><body>
            <h1>${coordinate.get()}</h1>
            <p>Android calling SDK. Read <a href="README.md">the quick start</a> and
            <a href="docs/README.md">the integration guides</a>. Kotlin KDoc is included in the sources artifact.</p>
            <h2>Reviewed public JVM declarations</h2>
            <p>Aggregate modules export APIs through their dependencies.</p><pre>$escaped</pre>
            <p><a href="LICENSE">Apache License 2.0</a> · <a href="NOTICE">Notices</a></p>
            </body></html>
            """
                    .trimIndent(),
            )
    }
}

abstract class GenerateSdkNotices : DefaultTask() {
    @get:Input abstract val artifactId: Property<String>
    @get:InputFile abstract val licenseFile: RegularFileProperty
    @get:InputFile abstract val noticeFile: RegularFileProperty
    @get:Optional @get:InputDirectory abstract val thirdPartyDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val destination = output.resolve("META-INF/${artifactId.get()}").apply { mkdirs() }
        licenseFile.get().asFile.copyTo(destination.resolve("LICENSE"), overwrite = true)
        noticeFile.get().asFile.copyTo(destination.resolve("NOTICE"), overwrite = true)
        thirdPartyDirectory.orNull
            ?.asFile
            ?.copyRecursively(destination.resolve("third-party"), overwrite = true)
    }
}

fun Project.configureSdkPublication() {
    apply(plugin = "com.vanniktech.maven.publish.base")

    group = providers.gradleProperty("sdkGroup").get()
    version = providers.gradleProperty("sdkVersion").get()

    val sdkArtifactId =
        when (project.name) {
            "sdk-starter" -> "directstream"
            else ->
                "directstream-" +
                    project.name.removePrefix("sdk").removePrefix("-").ifEmpty { "runtime" }
        }
    val centralRelease =
        providers.gradleProperty("sdkCentralRelease").map(String::toBoolean).getOrElse(false)

    extensions.configure<MavenPublishBaseExtension> {
        coordinates(project.group.toString(), sdkArtifactId, project.version.toString())
        val automaticRelease =
            providers.gradleProperty("sdkAutomaticRelease").map(String::toBoolean).getOrElse(false)
        require(!automaticRelease || centralRelease) {
            "Automatic release requires -PsdkCentralRelease=true."
        }
        // Local uploads stay manual; the tag release workflow explicitly opts into automatic
        // release.
        publishToMavenCentral(automaticRelease = automaticRelease)
        if (centralRelease) signAllPublications()
    }

    val sdkDocumentation =
        tasks.register<GenerateSdkDocumentation>("generateSdkDocumentation") {
            coordinate.set("${project.group}:$sdkArtifactId:${project.version}")
            baseline.set(rootProject.layout.projectDirectory.file("api/${project.name}.api"))
            outputDirectory.set(layout.buildDirectory.dir("generated/sdk-documentation"))
        }
    val sdkJavadocJar =
        tasks.register<Jar>("sdkJavadocJar") {
            archiveClassifier.set("javadoc")
            from(sdkDocumentation)
            from(
                rootProject.file("README.md"),
                rootProject.file("LICENSE"),
                rootProject.file("NOTICE"),
            )
            from(rootProject.file("docs")) { into("docs") }
            from(rootProject.file("sdk-ui/licenses")) { into("third-party") }
        }

    // Unique paths avoid duplicate META-INF entries when all five AARs are used together.
    val sdkNotices =
        tasks.register<GenerateSdkNotices>("generateSdkNotices") {
            artifactId.set(sdkArtifactId)
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            noticeFile.set(rootProject.layout.projectDirectory.file("NOTICE"))
            if (project.name == "sdk-ui")
                thirdPartyDirectory.set(layout.projectDirectory.dir("licenses"))
            outputDirectory.set(layout.buildDirectory.dir("generated/sdk-notices"))
        }
    extensions.configure<LibraryAndroidComponentsExtension> {
        onVariants(selector().all()) { variant ->
            variant.sources.resources?.addGeneratedSourceDirectory(
                sdkNotices,
                GenerateSdkNotices::outputDirectory,
            )
        }
    }
    tasks
        .withType<Jar>()
        .matching { it.name == "sourceReleaseJar" || it.name.endsWith("SourcesJar") }
        .configureEach {
            from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
        }

    afterEvaluate {
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("release") {
                from(components["release"])
                artifactId = sdkArtifactId
                artifact(sdkJavadocJar)
                pom {
                    name.set("DirectStream SDK ${project.name}")
                    description.set(
                        "Android one-to-one audio/video calling SDK with explicit call acceptance.",
                    )
                    url.set("https://github.com/Orthoepiccrown0/webrtc-android")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("orthoepiccrown0")
                            name.set("Orthoepiccrown0")
                            url.set("https://github.com/Orthoepiccrown0")
                        }
                    }
                    scm {
                        url.set("https://github.com/Orthoepiccrown0/webrtc-android")
                        connection.set(
                            "scm:git:https://github.com/Orthoepiccrown0/webrtc-android.git",
                        )
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/Orthoepiccrown0/webrtc-android.git",
                        )
                    }
                }
            }
            repositories.maven {
                name = "SdkLocal"
                url = rootProject.layout.buildDirectory.dir("sdk-maven").get().asFile.toURI()
            }
        }
    }

    // The Central prepare task can schedule a real upload at build completion, so guard it too.
    tasks
        .matching {
            it.name.contains("MavenCentral") &&
                (it.name.startsWith("prepare") ||
                    it.name.startsWith("publish") ||
                    it.name.startsWith("enableAutomatic"))
        }
        .configureEach { dependsOn(rootProject.tasks.named("validateCentralRelease")) }
    tasks.withType<org.gradle.plugins.signing.Sign>().configureEach {
        dependsOn(rootProject.tasks.named("validateSdkSigning"))
    }
}

subprojects {
    plugins.withId("com.android.library") { configureSdkPublication() }
}
