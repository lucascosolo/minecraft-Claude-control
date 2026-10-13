plugins { base }

val stagePlugin by tasks.registering(Copy::class) {
    dependsOn(":core:shadowJar")
    from(layout.projectDirectory.dir("core/build/libs")) { include("ClaudeControl.jar") }
    into(layout.buildDirectory.dir("libs"))
}

tasks.named("check") { dependsOn(":core:test") }
tasks.named("build") { dependsOn(":core:build", stagePlugin) }

// Historical restoration audit is separate from the independently rebuilt core.
tasks.register<Exec>("verifyRestoration") {
    commandLine("python3", "tools/check_restoration.py")
}
