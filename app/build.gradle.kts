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
    mavenLocal()
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
    // Coverage is reported over *every* tier, not just the unit tests: a line only
    // exercised by the BDD or integration suite is still covered, and pretending
    // otherwise understates the safety net. Each Test task writes its own
    // `build/jacoco/<task>.exec`, so the report simply consumes whatever is there.
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        }
    )

    // Deliberately no `dependsOn` on the test tasks. CI runs the tiers in parallel
    // jobs and feeds their execution data back in as artifacts, so the report must
    // be producible without re-running a single test. When tiers *are* run in the
    // same invocation, the ordering below still puts the report last.
    mustRunAfter(tasks.withType<Test>())

    // Same Jandex ordering caveat as the Javadoc task below: the analysed class
    // directories include the resources output that `jandex` writes the bean
    // index into, so the ordering has to be spelled out.
    mustRunAfter(tasks.named("jandex"))

    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    // Verified over the same aggregated execution data as the report above: the
    // threshold is a statement about the suite as a whole, not about the unit tier.
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        }
    )

    // Same reasoning as the report: no `dependsOn` on the test tasks, so the check
    // can be applied to execution data gathered elsewhere (CI collects it from five
    // parallel jobs) without re-running a single test.
    mustRunAfter(tasks.withType<Test>())
    mustRunAfter(tasks.named("jandex"))

    // A real dependency, not just ordering. A failed threshold is only actionable
    // with the report in hand - "coverage is 84%" is a number, the HTML report is
    // the list of lines to go and test. Depending on it means the report exists
    // *whenever* this task fails, rather than only when someone remembered to ask
    // for both tasks on the command line.
    dependsOn(tasks.jacocoTestReport)

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.85".toBigDecimal()
            }
        }
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
