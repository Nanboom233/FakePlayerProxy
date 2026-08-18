import java.util.Properties
import org.ajoberstar.grgit.Grgit

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.ajoberstar.grgit:grgit-core:5.3.3")
    }
}

plugins {
    java
}

group = rootProject.group
version = rootProject.version

layout.buildDirectory.set(layout.projectDirectory.dir(".gradle-build"))

val velocityBasePropertiesFile = layout.projectDirectory.file("patch/velocity-base.properties")
val velocityPatchFiles = fileTree(layout.projectDirectory.dir("patch")) {
    include("0001-login-relay.patch")
    include("0002-automation-extension.patch")
}
val velocityPatchTests = layout.projectDirectory.dir("patch/test")
val velocityCommit = requireNotNull(Properties().apply {
    velocityBasePropertiesFile.asFile.inputStream().use(::load)
}.getProperty("commit")) {
    "Missing commit in ${velocityBasePropertiesFile.asFile}"
}
val serverReleaseDirectory = layout.projectDirectory.dir("build/server/release")
val releasedVelocityJar = serverReleaseDirectory.file("velocity.jar")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

fun removeVelocityCheckout(work: File, failure: Throwable? = null) {
    try {
        if (work.exists() && System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            work.walkBottomUp().forEach { it.setWritable(true) }
        }
        if (work.exists()) {
            check(work.deleteRecursively()) { "Unable to delete disposable Velocity checkout: $work" }
        }
        check(!work.exists()) { "Disposable Velocity checkout still exists: $work" }
    } catch (cleanupException: Throwable) {
        if (failure == null) {
            throw cleanupException
        }
        failure.addSuppressed(cleanupException)
    }
}

fun prepareVelocityCheckout(copyTests: Boolean): File {
    val source = layout.projectDirectory.dir("build/server/source").asFile
    val work = layout.projectDirectory.dir("build/server/work").asFile
    check(source.isDirectory) { "Missing fixed Velocity source checkout: $source" }
    removeVelocityCheckout(work)
    try {
        work.parentFile.mkdirs()
        Grgit.open(mapOf("dir" to source)).use { sourceRepository ->
            check(sourceRepository.head().id == velocityCommit) {
                "Velocity source is not pinned to $velocityCommit"
            }
            check(sourceRepository.status().isClean) {
                "Velocity source checkout must remain clean"
            }

            val buildBranch = "fakeplayerproxy-build-${ProcessHandle.current().pid()}-${System.nanoTime()}"
            sourceRepository.branch.add(mapOf(
                "name" to buildBranch,
                "startPoint" to velocityCommit,
            ))
            var repositoryFailure: Throwable? = null
            try {
                Grgit.clone(mapOf(
                    "dir" to work,
                    "uri" to source.toURI().toString(),
                    "depth" to 1,
                    "checkout" to false,
                    "branches" to listOf("refs/heads/$buildBranch"),
                )).use { repository ->
                    repository.checkout(mapOf("branch" to velocityCommit))
                    velocityPatchFiles.files.sortedBy { it.name }.forEach { patch ->
                        repository.apply(mapOf("patch" to patch))
                    }
                }
            } catch (exception: Throwable) {
                repositoryFailure = exception
                throw exception
            } finally {
                try {
                    sourceRepository.branch.remove(mapOf(
                        "names" to listOf(buildBranch),
                        "force" to true,
                    ))
                } catch (cleanupException: Throwable) {
                    if (repositoryFailure == null) {
                        throw cleanupException
                    }
                    repositoryFailure.addSuppressed(cleanupException)
                }
            }
        }
        if (copyTests && velocityPatchTests.asFile.exists()) {
            velocityPatchTests.asFile.copyRecursively(work, overwrite = true)
        }
        return work
    } catch (exception: Throwable) {
        removeVelocityCheckout(work, exception)
        throw exception
    }
}

fun runVelocityGradle(work: File, vararg arguments: String) {
    val command = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        arrayOf("cmd", "/d", "/c", "gradlew.bat", *arguments)
    } else {
        arrayOf("./gradlew", *arguments)
    }
    providers.exec {
        workingDir(work)
        commandLine(*command)
    }.result.get().assertNormalExitValue()
}

tasks.register("assembleVelocityHost") {
    description = "Builds patched Velocity in a disposable local checkout."
    inputs.file(velocityBasePropertiesFile)
    inputs.files(velocityPatchFiles)
    outputs.file(releasedVelocityJar)

    doLast {
        val work = prepareVelocityCheckout(copyTests = false)
        var buildFailure: Throwable? = null
        try {
            runVelocityGradle(work, ":velocity-proxy:shadowJar")
            val velocityJars = work.resolve("proxy/build/libs")
                .listFiles { file -> file.name.endsWith("-all.jar") }
                ?.toList()
                .orEmpty()
            check(velocityJars.size == 1) {
                "Expected one patched Velocity jar, found: ${velocityJars.joinToString { it.name }}"
            }
            serverReleaseDirectory.asFile.mkdirs()
            velocityJars.single().copyTo(releasedVelocityJar.asFile, overwrite = true)
        } catch (exception: Throwable) {
            buildFailure = exception
            throw exception
        } finally {
            removeVelocityCheckout(work, buildFailure)
        }
    }
}

val velocityHost = configurations.create("velocityHost") {
    isCanBeConsumed = false
    isCanBeResolved = false
}

configurations.compileOnly {
    extendsFrom(velocityHost)
}

configurations.testCompileOnly {
    extendsFrom(configurations.compileOnly.get())
}

configurations.testRuntimeOnly {
    extendsFrom(velocityHost)
}

dependencies {
    velocityHost(files(releasedVelocityJar).builtBy(tasks.named("assembleVelocityHost")))
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("fakeplayerproxy.velocityJar", releasedVelocityJar.asFile.absolutePath)
}

tasks.jar {
    archiveBaseName.set("fake-player-proxy")
}

tasks.register("patchCheck") {
    group = "verification"
    description = "Applies production patches, copies external tests, and runs Velocity tests."
    inputs.file(velocityBasePropertiesFile)
    inputs.files(velocityPatchFiles)
    inputs.dir(velocityPatchTests)

    doLast {
        val work = prepareVelocityCheckout(copyTests = true)
        var buildFailure: Throwable? = null
        try {
            runVelocityGradle(work, ":velocity-api:test", ":velocity-proxy:test")
        } catch (exception: Throwable) {
            buildFailure = exception
            throw exception
        } finally {
            removeVelocityCheckout(work, buildFailure)
        }
    }
}

val pluginJarFile = tasks.jar.flatMap { it.archiveFile }
val releasedPluginJar = pluginJarFile.map { serverReleaseDirectory.file(it.asFile.name) }

tasks.register("releaseJar") {
    group = "server"
    description = "Builds patched Velocity and the FakePlayerProxy plugin."
    dependsOn(tasks.jar)
    inputs.file(pluginJarFile)
    outputs.file(releasedPluginJar)

    doLast {
        serverReleaseDirectory.asFile.mkdirs()
        pluginJarFile.get().asFile.copyTo(releasedPluginJar.get().asFile, overwrite = true)
    }
}

tasks.register<Exec>("runServer") {
    group = "server"
    description = "Runs the released patched Velocity server from plugin/run."
    dependsOn(tasks.named("releaseJar"))
    workingDir(layout.projectDirectory.dir("run"))
    standardInput = System.`in`

    doFirst {
        val pluginJar = pluginJarFile.get().asFile
        val runPlugins = layout.projectDirectory.dir("run/plugins").asFile
        runPlugins.mkdirs()
        serverReleaseDirectory.file(pluginJar.name).asFile
            .copyTo(runPlugins.resolve(pluginJar.name), overwrite = true)
    }

    commandLine(
        "java",
        "-Xms1G",
        "-Xmx1G",
        "-XX:+UseG1GC",
        "-jar",
        releasedVelocityJar.asFile.absolutePath,
    )
}
