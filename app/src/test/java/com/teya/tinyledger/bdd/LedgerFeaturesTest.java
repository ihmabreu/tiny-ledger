package com.teya.tinyledger.bdd;

import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import org.junit.jupiter.api.Tag;

/**
 * Runs the Gherkin feature files against a live application instance.
 *
 * <p>{@link CucumberQuarkusTest} boots Quarkus exactly as {@code @QuarkusTest} does, so the
 * scenarios exercise the real HTTP stack rather than a stubbed one. The features are written in
 * business language and are the acceptance criteria for the ledger; the step definitions in
 * {@link LedgerSteps} are the only place that knows about HTTP.</p>
 *
 * <p><strong>Why both {@code glue} and {@code features} are pinned:</strong> without any
 * {@link CucumberOptions} annotation Cucumber defaults to scanning the <em>entire</em> classpath
 * for step definitions, which is wasteful and pulls every test-scope dependency into the scan.
 * Declaring {@code glue} narrows that to this package, where all step definitions and hooks live.
 * The catch is that the annotation also flips the feature-path default from "the whole classpath"
 * to "the annotated class's own package" &mdash; and no {@code .feature} files live here, so
 * declaring {@code glue} alone would silently discover zero scenarios and still report green.
 * {@code features} is therefore pinned to {@code classpath:features}, matching the real location
 * under {@code src/test/resources/features}.</p>
 */
@Tag("bdd")
@CucumberOptions(glue = "com.teya.tinyledger.bdd", features = "classpath:features")
public class LedgerFeaturesTest extends CucumberQuarkusTest {

    /**
     * Allows the features to be run directly from an IDE or the command line.
     *
     * @param args standard Cucumber CLI arguments
     */
    public static void main(String[] args) {
        runMain(LedgerFeaturesTest.class, args);
    }
}
