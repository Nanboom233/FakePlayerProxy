plugins {
    base
}

group = "com.fakeplayerproxy"
version = "0.1.0"

tasks.named("build") {
    dependsOn(":plugin:build", ":mod:build")
}
