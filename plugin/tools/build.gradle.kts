plugins {
    kotlin("jvm") version "2.3.0"
}

group = rootProject.group
version = rootProject.version

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("plugin-tools"))

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.setSrcDirs(listOf(layout.projectDirectory))
        kotlin.include("GenResources.kt")
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
}

tasks.register<JavaExec>("genResources") {
    group = "generation"
    description = "Generates the fixed Minecraft 26.2 runtime data."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("GenResourcesKt")
    workingDir(layout.projectDirectory)

    val minecraftDataDirectory = providers.gradleProperty("minecraftDataDir")
    args(
        minecraftDataDirectory,
        project(":plugin").layout.projectDirectory.dir("src/main/resources/minecraft-data").asFile,
    )
}
