plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.lucascosolo"
version = "0.4.0"

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.133-stable")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test { useJUnitPlatform() }
    processResources {
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    jar { archiveClassifier = "plain" }
    shadowJar {
        archiveFileName = "ClaudeControl.jar"
        archiveClassifier = ""
        relocate("com.google.gson", "com.lucascosolo.claudecontrol.core.lib.gson")
    }
    build { dependsOn(shadowJar) }
}
