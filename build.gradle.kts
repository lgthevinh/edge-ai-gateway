plugins {
    id("java")
}

group = "thingai.edge.agent"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(files("libs/applicationbase.jar"))
    implementation(files("libs/desktopplatform.jar"))

    // appbase and desktopplatform dependencies
    implementation("org.xerial:sqlite-jdbc:3.43.2.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.google.code.gson:gson:2.13.2")

    // mcp core without json, use gson instead
    implementation("io.modelcontextprotocol.sdk:mcp-core:1.1.1")

    // javalin
    implementation("io.javalin:javalin:7.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    // llama.cpp bindings
    implementation("de.kherud:llama:4.1.0")
}

tasks.test {
    useJUnitPlatform()
}