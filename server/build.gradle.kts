plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:2.3.8")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.8")
    implementation("io.ktor:ktor-server-websockets-jvm:2.3.8")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.8")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Database Persistence (Exposed, Postgres, SQLite, HikariCP)
    val exposedVersion = "0.48.0"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.2")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("software.amazon.awssdk:s3:2.25.15")
    
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.register<JavaExec>("run") {
    mainClass.set("com.androidprotect.server.AppKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.androidprotect.server.AppKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
