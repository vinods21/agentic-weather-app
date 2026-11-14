import java.util.Properties
import java.util.UUID

plugins {
    java
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "Weather service (Spring Boot, PostgreSQL, Redis)"

// Target Java 21 bytecode/APIs regardless of which (newer) JDK runs Gradle.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // NOTE: never add Testcontainers (or anything that starts containers from tests).
    // Integration tests use the Docker Compose services; see CLAUDE.md.
}

// ---------------------------------------------------------------------------
// Test suites
//   test            -> unit tests, src/test, no Docker needed (part of `build`)
//   integrationTest -> src/integrationTest, needs `docker compose up -d --wait`
// ---------------------------------------------------------------------------

/** Reads KEY=VALUE pairs from .env (or .env.example) so host-run tests match compose. */
fun composeEnv(): Map<String, String> {
    val file = listOf(".env", ".env.example").map { rootProject.file(it) }.firstOrNull { it.exists() }
        ?: return emptyMap()
    val props = Properties()
    file.reader().use { props.load(it) }
    return props.stringPropertyNames().associateWith { props.getProperty(it).trim().removeSurrounding("\"") }
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-jdbc")
                implementation("org.springframework.boot:spring-boot-starter-data-redis")
                implementation("org.junit.platform:junit-platform-launcher")
            }
            targets.all {
                testTask.configure {
                    description = "Integration tests against the running Docker Compose services."
                    shouldRunAfter(tasks.named("test"))
                    // Integration tests must always hit the live services, never a cached result.
                    outputs.upToDateWhen { false }
                    val runId = UUID.randomUUID().toString().replace("-", "").take(10)
                    systemProperty("spring.profiles.active", "it")
                    systemProperty("it.run-id", runId)
                    // Env from the process wins over .env, so CI can override.
                    composeEnv().forEach { (k, v) -> if (System.getenv(k) == null) environment(k, v) }
                    testLogging { events("passed", "skipped", "failed") }
                }
            }
        }
    }
}

tasks.named<Test>("test") {
    testLogging { events("failed") }
}
