import org.gradle.internal.os.OperatingSystem

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// --- Frontend integration -------------------------------------------------
// `./gradlew :server:bootRun` (or bootJar) builds web/ with npm and bundles the
// output as static resources when web/node_modules exists. Pass -PskipWeb to
// skip, or run `npm run dev` in web/ for hot reload against the running server.

val webDir = rootProject.layout.projectDirectory.dir("web")
val npmCommand = if (OperatingSystem.current().isWindows) "npm.cmd" else "npm"

val buildWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Vite frontend in web/."
    workingDir = webDir.asFile
    commandLine(npmCommand, "run", "build")
    inputs.dir(webDir.dir("src"))
    inputs.files(webDir.file("package.json"), webDir.file("vite.config.ts"), webDir.file("index.html"))
    outputs.dir(webDir.dir("dist"))
    onlyIf {
        val enabled = !project.hasProperty("skipWeb") && webDir.dir("node_modules").asFile.exists()
        if (!enabled) logger.lifecycle("Skipping frontend build (web/node_modules missing or -PskipWeb).")
        enabled
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildWeb)
    from(webDir.dir("dist")) {
        into("static")
    }
}

// --- Desktop app (jpackage) ------------------------------------------------
// `./gradlew :server:jpackage` makes a self-contained app (bundled runtime, no Java
// install needed) for the OS you run it on: build/jpackage/<name>/ with a launcher
// that starts the server, opens the dashboard and sits in the tray. Windows packages
// must be built on Windows (CI's windows-app job does this); -PjpackageType=msi
// produces an installer when WiX is installed.

val appName = "AI CLI Orchestrator"
val jpackageInput = layout.buildDirectory.dir("jpackage-input")

val prepareJpackage by tasks.registering(Sync::class) {
    dependsOn(tasks.named("bootJar"))
    from(tasks.named("bootJar"))
    into(jpackageInput)
}

val jpackage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Packages the server + web UI as a desktop app with a bundled Java runtime."
    dependsOn(prepareJpackage)
    val type = (project.findProperty("jpackageType") as String?) ?: "app-image"
    val dest = layout.buildDirectory.dir("jpackage")
    val javaHome = System.getProperty("java.home")
    val exe = if (OperatingSystem.current().isWindows) "jpackage.exe" else "jpackage"
    val mainJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar").flatMap { it.archiveFileName }
    doFirst {
        delete(dest)
        dest.get().asFile.mkdirs()
    }
    val args = mutableListOf(
        "$javaHome/bin/$exe",
        "--type", type,
        "--name", appName,
        "--app-version", project.version.toString(),
        "--vendor", "ReactiveGG",
        "--description", "Local orchestrator for Claude/Codex CLI agents: planner → coder → reviewer → verifier",
        "--input", jpackageInput.get().asFile.path,
        "--main-jar", mainJar.get(),
        "--dest", dest.get().asFile.path,
        // Only what Spring Boot + AWT tray + the CLI modules touch; java.se would double the runtime.
        "--add-modules", "java.base,java.logging,java.management,java.naming,java.sql,java.xml,java.desktop,java.instrument,"
                + "java.net.http,java.security.jgss,java.security.sasl,java.scripting,java.prefs,jdk.unsupported,jdk.crypto.ec,"
                + "jdk.zipfs,jdk.charsets",   // no jdk.localedata: the server formats nothing locale-specific (the browser does), saves ~25MB
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=zip-6",
        "--java-options", "-Dorchestrator.desktop.enabled=true",
        "--java-options", "-Djava.awt.headless=false",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dlogging.file.name=\${user.home}/.ai-orchestrator/server.log",
        "--java-options", "-Xmx512m",
        "--icon", layout.projectDirectory.file(if (OperatingSystem.current().isWindows) "src/main/jpackage/icon.ico" else "src/main/jpackage/icon.png").asFile.path,
    )
    if (OperatingSystem.current().isWindows) {
        if (type != "app-image") {
            args.addAll(listOf("--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-per-user-install", "--win-menu-group", appName))
        }
    } else if (OperatingSystem.current().isMacOsX) {
        args.addAll(listOf("--mac-package-identifier", "dev.orchestrator.server"))
    }
    commandLine(args)
    doLast {
        logger.lifecycle("Packaged $appName ($type) into ${dest.get().asFile}")
    }
}
