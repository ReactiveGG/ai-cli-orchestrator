plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.7")
}

application {
    mainClass.set("dev.orchestrator.cli.App")
}
