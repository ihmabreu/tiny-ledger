package com.teya.tinyledger.integration;

import com.teya.tinyledger.support.OpenApiContract;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies that the application publishes the reviewed contract, and that the API is versioned.
 *
 * <p>Serving a generated document instead of the hand-written one would let the implementation
 * silently redefine the contract, which defeats the point of writing it first. Annotation
 * scanning is therefore disabled and this test proves the served document is the file under
 * source control.</p>
 */
@QuarkusTest
@Tag("integration")
@DisplayName("Published API contract")
class ApiDocumentTest {

    @Test
    @DisplayName("serves the hand-written contract, not a generated one")
    void getOpenApi_yamlEndpoint_servesCommittedContract() {
        String served = RestAssured.given()
                .accept("application/yaml")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().asString();

        assertThat(served).contains("Tiny Ledger API");
        assertThat(served).contains("/api/v1/accounts");
        assertThat(served).contains("operationId: recordTransaction");
    }

    @Test
    @DisplayName("declares an explicit API version")
    void contract_specificationFile_declaresExplicitVersion() {
        assertThat(OpenApiContract.readSpecification()).containsPattern("version:\\s*\"?1\\.");
    }

    @Test
    @DisplayName("exposes Swagger UI so a running instance is always explorable")
    void getSwaggerUi_endpoint_returnsOkWithSwaggerUiHtml() {
        RestAssured.when().get("/q/swagger-ui")
                .then()
                .statusCode(200)
                .body(containsString("swagger"));
    }

    @Test
    @DisplayName("versions every endpoint under /api/v1")
    void contract_allDeclaredPaths_versionedUnderApiV1() {
        String specification = OpenApiContract.readSpecification();

        specification.lines()
                .map(String::stripTrailing)
                .filter(line -> line.startsWith("  /"))
                .forEach(path -> assertThat(path.strip()).startsWith("/api/v1/"));
    }

    @Test
    @DisplayName("an unversioned path is not served")
    void getUnversionedPath_unversionedEndpoint_returnsNotFound() {
        RestAssured.when().get("/accounts").then().statusCode(404);
    }
}
