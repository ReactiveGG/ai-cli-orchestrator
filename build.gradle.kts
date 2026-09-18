plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// Optional: redirect build output off the project tree, e.g. on WSL where the
// repo sits on a Windows mount (/mnt/c) that rejects chmod. Set it once in
// ~/.gradle/gradle.properties:  buildDirBase=/home/<you>/.cache/ai-cli-orchestrator
val buildDirBase = providers.gradleProperty("buildDirBase").orNull

subprojects {
    apply(plugin = "java")

    group = "dev.orchestrator"
    version = "0.1.2"

    if (buildDirBase != null) {
        layout.buildDirectory.set(File(buildDirBase, name))
    }

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
