plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

group = rootProject.group
version = rootProject.version

base {
    archivesName.set("fakeplayerproxy-mod")
}

repositories {
    maven("https://maven.shedaniel.me/")
    maven("https://maven.terraformersmc.com/releases/")
}

java {
    withSourcesJar()
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    // Minecraft 26.2 is distributed with official names and has no mappings artifact.
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("me.shedaniel.cloth:cloth-config-fabric:26.2.155")
    compileOnly("com.terraformersmc:modmenu:20.0.1") {
        isTransitive = false
    }
    compileOnly("org.jetbrains:annotations:26.0.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}
