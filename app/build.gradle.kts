import org.gradle.api.tasks.testing.Test

plugins {
    java
    jacoco
    alias(libs.plugins.quarkus)
    alias(libs.plugins.jandex)
}

description = "Tiny Ledger REST service (Quarkus, imperative + virtual threads)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.platform.bom))

    // Imperative (blocking) JAX-RS stack - deliberately NOT the Mutiny reactive model,
    // because scalability here comes from virtual threads (see docs/CONCURRENCY.md).
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-hibernate-validator")

    // API-first: serves the hand-written OpenAPI contract + Swagger UI.
    implementation("io.quarkus:quarkus-smallrye-openapi")

    // Multi-architecture (amd64/arm64) container images without a Docker daemon.
    implementation("io.quarkus:quarkus-container-image-jib")

    // YAML application config (application.yml) instead of application.properties.
    // Not bundled in quarkus-core: SmallRye Config only registers a YAML ConfigSource
    // when this extension is on the classpath, so it is required, not cosmetic.
    implementation("io.quarkus:quarkus-config-yaml")

    // Quarkus copies the test runtime classpath into its own configurations, which do not
    // inherit `implementation`; the platform therefore has to be declared here as well.
    testImplementation(enforcedPlatform(libs.quarkus.platform.bom))

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testImplementation(libs.quarkus.cucumber)
    testImplementation(libs.swagger.request.validator.restassured)
}

/**
 * Shared configuration for every test tier.
 *
 * All tiers share one test source set and are separated by JUnit 5 tags, each running in its
 * own JVM. This matters for Quarkus: `@QuarkusTest` and `@QuarkusIntegrationTest` classes must
 * never be executed inside the same JVM.
 */
fun Test.configureTestTier(tag: String) {
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags(tag)
    }
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    description = "Runs the fast, isolated unit tests (tag: unit)."
    configureTestTier("unit")
    finalizedBy(tasks.jacocoTestReport)
}

val bddTest = tasks.register<Test>("bddTest") {
    description = "Runs the Cucumber BDD acceptance scenarios (tag: bdd)."
    configureTestTier("bdd")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs HTTP integration + OpenAPI contract tests (tag: integration)."
    configureTestTier("integration")
}

val concurrencyTest = tasks.register<Test>("concurrencyTest") {
    description = "Runs virtual-thread concurrency stress tests (tag: concurrency)."
    configureTestTier("concurrency")
}

val e2eTest = tasks.register<Test>("e2eTest") {
    description = "Runs black-box end-to-end tests against the packaged artifact (tag: e2e)."
    configureTestTier("e2e")
    dependsOn(tasks.named("quarkusBuild"))
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.check {
    dependsOn(bddTest, integrationTest, concurrencyTest, e2eTest)
}

tasks.javadoc {
    // The Jandex plugin writes the bean index into the same resources output directory that
    // Javadoc reads from, so Gradle needs the ordering spelled out.
    mustRunAfter(tasks.named("jandex"))
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        encoding = "UTF-8"
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
