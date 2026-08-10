import java.util.Properties

plugins {
    java
}

group = rootProject.group
version = rootProject.version

val velocityApiVersion = "3.4.0"
// Build 15 supports Java 17. Build 16 requires Java 21.
val mcProtocolLibVersion = "26.2-20260709.110151-15"
// MCProtocolLib build 15 declares Netty 4.2.1. Use the current 4.2 release.
val nettyVersion = "4.2.17.Final"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion") {
        exclude(group = "com.google.guava")
        exclude(group = "org.yaml", module = "snakeyaml")
    }
    compileOnly("com.google.inject:guice:7.0.0") {
        isTransitive = false
    }
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    implementation(platform("io.netty:netty-bom:$nettyVersion"))
    implementation("org.geysermc.mcprotocollib:protocol:$mcProtocolLibVersion") {
        exclude(group = "io.netty")
    }
    implementation("io.netty:netty-all:$nettyVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("fake-player-proxy")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })

    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

val velocityBasePropertiesFile = layout.projectDirectory.file("patch/velocity-base.properties")
val velocityPatchFile = layout.projectDirectory.file("patch/0001-server-hello-marker.patch")
val velocityBaseProperties = Properties().apply {
    velocityBasePropertiesFile.asFile.inputStream().use(::load)
}
val velocityRepository = requireNotNull(velocityBaseProperties.getProperty("repository")) {
    "Missing repository in ${velocityBasePropertiesFile.asFile}"
}
val velocityCommit = requireNotNull(velocityBaseProperties.getProperty("commit")) {
    "Missing commit in ${velocityBasePropertiesFile.asFile}"
}
val serverBuildDirectory = layout.buildDirectory.dir("server")
val patchedVelocityDirectory = serverBuildDirectory.map { it.dir("source") }
val serverReleaseDirectory = serverBuildDirectory.map { it.dir("release") }
val releasedVelocityJar = serverReleaseDirectory.map { it.file("velocity.jar") }

fun execute(directory: File, vararg command: String) {
    providers.exec {
        workingDir(directory)
        commandLine(*command)
    }.result.get().assertNormalExitValue()
}

val releaseJar = tasks.register("releaseJar") {
    group = "server"
    description = "Builds patched Velocity and the FakePlayerProxy plugin."
    dependsOn(tasks.jar)
    inputs.files(velocityBasePropertiesFile, velocityPatchFile, tasks.jar.flatMap { it.archiveFile })
    outputs.dir(serverReleaseDirectory)

    doLast {
        val serverBuild = serverBuildDirectory.get().asFile
        val patchedVelocity = patchedVelocityDirectory.get().asFile
        val releaseDirectory = serverReleaseDirectory.get().asFile
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            serverBuild.walkBottomUp().forEach { it.setWritable(true) }
        }
        check(serverBuild.deleteRecursively()) {
            "Unable to clear disposable Velocity build directory: $serverBuild"
        }
        check(serverBuild.mkdirs()) {
            "Unable to create disposable Velocity build directory: $serverBuild"
        }

        execute(
            serverBuild,
            "git",
            "clone",
            "--filter=blob:none",
            "--no-checkout",
            velocityRepository,
            patchedVelocity.absolutePath,
        )
        execute(patchedVelocity, "git", "checkout", "--detach", velocityCommit)
        execute(patchedVelocity, "git", "apply", velocityPatchFile.asFile.absolutePath)

        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            execute(
                patchedVelocity,
                "cmd",
                "/d",
                "/c",
                "gradlew.bat",
                ":velocity-proxy:shadowJar",
            )
        } else {
            execute(patchedVelocity, "./gradlew", ":velocity-proxy:shadowJar")
        }

        val velocityJars = patchedVelocity.resolve("proxy/build/libs")
            .listFiles { file -> file.name.endsWith("-all.jar") }
            ?.toList()
            .orEmpty()
        check(velocityJars.size == 1) {
            "Expected one patched Velocity jar, found: ${velocityJars.joinToString { it.name }}"
        }

        releaseDirectory.mkdirs()
        velocityJars.single().copyTo(releasedVelocityJar.get().asFile, overwrite = true)
        val pluginJar = tasks.jar.get().archiveFile.get().asFile
        pluginJar.copyTo(releaseDirectory.resolve(pluginJar.name), overwrite = true)
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
        serverReleaseDirectory.get().asFile.resolve(pluginJar.name)
            .copyTo(runPlugins.resolve(pluginJar.name), overwrite = true)
    }

    commandLine(
        "java",
        "-Xms1G",
        "-Xmx1G",
        "-XX:+UseG1GC",
        "-jar",
        releasedVelocityJar.get().asFile.absolutePath,
    )
}
