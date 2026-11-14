plugins {
    java
}

group = "com.extantape"
version = "0.3.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.bluecolored.de/releases")
}

// Built against the oldest supported Paper, so nothing newer than 26.1 sneaks in.
val paperApi = "io.papermc.paper:paper-api:26.1.2.build.74-stable"
val blueMapApi = "de.bluecolored:bluemap-api:2.7.8"
// Shared with the test-only plugins in test/live.
extra["paperApi"] = paperApi
extra["blueMapApi"] = blueMapApi

dependencies {
    compileOnly(paperApi)
    compileOnly(blueMapApi)

    testImplementation(paperApi)
    testImplementation(blueMapApi)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // Shows Paper API deprecations as they arrive, so they can be dealt with before removal.
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

tasks.jar {
    archiveBaseName.set("BlueMapMini")
    from("LICENSE")
}
