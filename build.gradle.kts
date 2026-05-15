plugins {
    id("java")
}

group = "thingai.edge.agent"
version = "1.0-SNAPSHOT"

val clientDir = layout.projectDirectory.dir("client")
val clientDistDir = clientDir.dir("dist/client/browser")
val publicResourcesDir = layout.projectDirectory.dir("src/main/resources/public")

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(files("libs/applicationbase.jar"))
    implementation(files("libs/edgeplatform.jar"))
    implementation(files("libs/aisdk.jar"))

    // appbase and desktopplatform dependencies
    implementation("org.xerial:sqlite-jdbc:3.43.2.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.google.code.gson:gson:2.13.2")

    // mcp core without json, use gson instead
    implementation("io.modelcontextprotocol.sdk:mcp-core:1.1.2")

    // javalin
    implementation("io.javalin:javalin:7.2.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    // okhttp
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Exec>("buildClient") {
    workingDir = clientDir.asFile

    val npmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
        "npm.cmd"
    } else {
        "npm"
    }

    commandLine(npmCommand, "run", "build")

    inputs.files(
        fileTree(clientDir.dir("src")),
        clientDir.file("package.json"),
        clientDir.file("package-lock.json"),
        clientDir.file("angular.json")
    )
    outputs.dir(clientDistDir)
}

tasks.register<Sync>("syncClientResources") {
    dependsOn("buildClient")
    from(clientDistDir)
    into(publicResourcesDir)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn("syncClientResources")
}
