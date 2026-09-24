plugins {
    java
    alias(libs.plugins.gatling)
}

description = "Gatling load & performance tests for the Tiny Ledger service"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

/*
 * Simulations live in `src/gatling/java` and are written with the Gatling *Java* DSL
 * (no Scala), keeping the whole repository single-language.
 *
 * These tests intentionally run against an already-running instance of the service, so they
 * measure the real HTTP stack rather than an in-JVM harness:
 *
 *   Terminal 1:  ./gradlew :app:quarkusRun
 *   Terminal 2:  ./gradlew :load-tests:gatlingRun
 *
 * Override the target with:  -Dtinyledger.baseUrl=http://localhost:8080
 */
gatling {
    // Gatling's log writer reaches into java.lang internals to intern strings cheaply, which
    // the module system blocks by default on Java 17+.
    jvmArgs = listOf("-Xmx1G", "--add-opens", "java.base/java.lang=ALL-UNNAMED")
    systemProperties = mapOf(
        "tinyledger.baseUrl" to (System.getProperty("tinyledger.baseUrl") ?: "http://localhost:8080")
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
