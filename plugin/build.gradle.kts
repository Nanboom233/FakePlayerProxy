import java.util.Properties
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.TaskProvider

plugins {
    java
}

group = rootProject.group
version = rootProject.version

layout.buildDirectory.set(layout.projectDirectory.dir(".gradle-build"))

val mcProtocolLibVersion = "26.2-20260809.160751-16"
val nettyVersion = "4.2.17.Final"
val lombokVersion = "1.18.42"
val velocityBasePropertiesFile: RegularFile = layout.projectDirectory.file("patch/velocity-base.properties")
val velocityPatchDirectory: Directory = layout.projectDirectory.dir("patch")
val velocityPatchFiles: ConfigurableFileTree = fileTree(velocityPatchDirectory) {
    include("0001-login-relay.patch")
    include("0002-automation-extension.patch")
}
val velocityPatchTests: Directory = layout.projectDirectory.dir("patch/test")
val velocityBaseProperties: Properties = Properties().apply {
    velocityBasePropertiesFile.asFile.inputStream().use(::load)
}
val velocityCommit: String = requireNotNull(velocityBaseProperties.getProperty("commit")) {
    "Missing commit in ${velocityBasePropertiesFile.asFile}"
}
val serverDirectory: Directory = layout.projectDirectory.dir("build/server")
val velocitySourceDirectory: Directory = serverDirectory.dir("source")
val velocityWorkDirectory: Directory = serverDirectory.dir("work")
val serverReleaseDirectory: Directory = serverDirectory.dir("release")
val releasedVelocityJar: RegularFile = serverReleaseDirectory.file("velocity.jar")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

fun execute(directory: File, vararg command: String) {
    providers.exec {
        workingDir(directory)
        commandLine(*command)
    }.result.get().assertNormalExitValue()
}

fun output(directory: File, vararg command: String): String {
    return providers.exec {
        workingDir(directory)
        commandLine(*command)
    }.standardOutput.asText.get().trim()
}

fun removeVelocityWorktree(source: File, work: File) {
    if (work.exists() && System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        work.walkBottomUp().forEach { it.setWritable(true) }
    }
    val registered = output(source, "git", "worktree", "list", "--porcelain")
        .lineSequence()
        .filter { it.startsWith("worktree ") }
        .map { File(it.removePrefix("worktree ")).canonicalFile }
        .any { it == work.canonicalFile }
    if (registered) {
        execute(source, "git", "worktree", "remove", "--force", work.absolutePath)
    }
    if (work.exists()) {
        check(work.deleteRecursively()) { "Unable to delete disposable Velocity worktree: $work" }
    }
    check(!work.exists()) { "Disposable Velocity worktree still exists: $work" }
    execute(source, "git", "worktree", "prune")
}

fun prepareVelocityWorktree(copyTests: Boolean): Pair<File, File> {
    val source = velocitySourceDirectory.asFile
    val work = velocityWorkDirectory.asFile
    check(source.isDirectory) { "Missing fixed Velocity source checkout: $source" }
    check(output(source, "git", "rev-parse", "HEAD") == velocityCommit) {
        "Velocity source is not pinned to $velocityCommit"
    }
    check(output(source, "git", "status", "--porcelain").isEmpty()) {
        "Velocity source checkout must remain clean"
    }

    removeVelocityWorktree(source, work)
    work.parentFile.mkdirs()
    execute(source, "git", "worktree", "add", "--detach", work.absolutePath, velocityCommit)
    velocityPatchFiles.files.sortedBy { patch ->
        patch.relativeTo(velocityPatchDirectory.asFile).invariantSeparatorsPath
    }.forEach { patch ->
        execute(work, "git", "apply", patch.absolutePath)
    }
    if (copyTests && velocityPatchTests.asFile.exists()) {
        velocityPatchTests.asFile.copyRecursively(work, overwrite = true)
    }
    return source to work
}

fun runVelocityGradle(work: File, vararg arguments: String) {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        execute(work, "cmd", "/d", "/c", "gradlew.bat", *arguments)
    } else {
        execute(work, "./gradlew", *arguments)
    }
}

val assembleVelocityHost: TaskProvider<Task> = tasks.register("assembleVelocityHost") {
    description = "Builds patched Velocity in a disposable Git worktree."
    inputs.file(velocityBasePropertiesFile)
    inputs.files(velocityPatchFiles)
    outputs.file(releasedVelocityJar)

    doLast {
        val (source, work) = prepareVelocityWorktree(copyTests = false)
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
        } finally {
            removeVelocityWorktree(source, work)
        }
    }
}

val velocityHost: Configuration = configurations.create("velocityHost") {
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
    velocityHost(files(releasedVelocityJar))
    compileOnly("org.geysermc.mcprotocollib:protocol:$mcProtocolLibVersion") {
        exclude(group = "io.netty")
    }
    compileOnly(platform("io.netty:netty-bom:$nettyVersion"))
    compileOnly("io.netty:netty-transport")
    compileOnly("com.google.inject:guice:7.0.0") {
        isTransitive = false
    }
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(assembleVelocityHost)
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    inputs.file(releasedVelocityJar)
    systemProperty("fakeplayerproxy.velocityJar", releasedVelocityJar.asFile.absolutePath)
}

tasks.jar {
    archiveBaseName.set("fake-player-proxy")
    archiveClassifier.set("")
}

tasks.register("patchCheck") {
    group = "verification"
    description = "Applies production patches, copies external tests, and runs Velocity tests."
    inputs.file(velocityBasePropertiesFile)
    inputs.files(velocityPatchFiles)
    inputs.dir(velocityPatchTests)

    doLast {
        val (source, work) = prepareVelocityWorktree(copyTests = true)
        try {
            runVelocityGradle(work, ":velocity-api:test", ":velocity-proxy:test")
        } finally {
            removeVelocityWorktree(source, work)
        }
    }
}

val releaseJar: TaskProvider<Task> = tasks.register("releaseJar") {
    group = "server"
    description = "Builds patched Velocity and the FakePlayerProxy plugin."
    dependsOn(assembleVelocityHost, tasks.jar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    outputs.dir(serverReleaseDirectory)

    doLast {
        serverReleaseDirectory.asFile.mkdirs()
        val pluginJar = tasks.jar.get().archiveFile.get().asFile
        pluginJar.copyTo(serverReleaseDirectory.file(pluginJar.name).asFile, overwrite = true)
    }
}

tasks.register<Exec>("runServer") {
    group = "server"
    description = "Runs the released patched Velocity server from plugin/run."
    dependsOn(releaseJar)
    workingDir(layout.projectDirectory.dir("run"))
    standardInput = System.`in`

    doFirst {
        val pluginJar = tasks.jar.get().archiveFile.get().asFile
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
