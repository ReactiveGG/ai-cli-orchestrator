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
