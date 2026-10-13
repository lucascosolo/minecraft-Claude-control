plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.lucascosolo"
version = "0.1.0"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("dev.folia:folia-api:26.1.2.build.8-stable")
    implementation("com.anthropic:anthropic-java:2.34.0")

    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Report missing recovered production files before compilation.
val verifyRestoration by tasks.registering {
    doLast {
        val requiredPaths = file("MANIFEST.tsv").readLines().drop(1)
            .map { it.substringBefore('\t') }
            .filter { it.startsWith("src/main/") }
        val missingPaths = requiredPaths.filter { !file(it).isFile }
        check(missingPaths.isEmpty()) {
            "Restoration incomplete; recover these files before building:\n" + missingPaths.joinToString("\n")
        }
    }
}

tasks {
    compileJava { dependsOn(verifyRestoration) }
    processResources { dependsOn(verifyRestoration) }
    test {
        useJUnitPlatform()
    }
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    jar {
        archiveClassifier = "plain"
    }
    shadowJar {
        archiveClassifier = ""
        // Exactly ClaudeControl.jar: the container boot guard treats every other plugins/Claude*.jar as Claude-written.
        archiveFileName = "ClaudeControl.jar"
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        dependencies {
            exclude(dependency("org.slf4j:.*"))
            exclude(dependency("org.jetbrains:annotations"))
        }
        exclude("META-INF/versions/*/module-info.class", "module-info.class", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        val lib = "com.lucascosolo.claudecontrol.lib"
        // Kotlin is not relocated: kotlin-reflect (used by jackson-module-kotlin) cannot find its builtins once moved.
        listOf("com.fasterxml", "okhttp3", "okio", "org.apache.hc", "com.github.victools",
            "com.standardwebhooks", "io.swagger", "com.google.errorprone").forEach { relocate(it, "$lib.$it") }
    }
    build {
        dependsOn(shadowJar)
    }
}
