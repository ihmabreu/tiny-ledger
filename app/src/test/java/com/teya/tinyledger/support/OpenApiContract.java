package com.teya.tinyledger.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Supplies a RestAssured filter that validates every request and response against the
 * hand-written OpenAPI contract.
 *
 * <p>This is what makes the project genuinely API-first rather than API-first-in-spirit: the
 * contract is not documentation produced after the fact, it is an executable assertion. A
 * response field that is renamed, retyped, or quietly added without updating
 * {@code META-INF/openapi.yaml} fails the build.</p>
 */
public final class OpenApiContract {

    private static final String SPECIFICATION_RESOURCE = "META-INF/openapi.yaml";

    private static final OpenApiValidationFilter FILTER = new OpenApiValidationFilter(
            OpenApiInteractionValidator
                    .createForInlineApiSpecification(readSpecification())
                    .build());

    private OpenApiContract() {
    }

    /**
     * Returns the shared validation filter.
     *
     * @return a filter asserting contract conformance of the exchange it observes
     */
    public static OpenApiValidationFilter validationFilter() {
        return FILTER;
    }

    /**
     * Reads the contract from the classpath.
     *
     * <p>The very same file the application serves is used, so the tests can never validate
     * against a stale copy that has drifted from what clients actually receive.</p>
     *
     * @return the contract as text
     */
    public static String readSpecification() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(SPECIFICATION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "OpenAPI contract not found on the classpath at " + SPECIFICATION_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the OpenAPI contract", e);
        }
    }
}
