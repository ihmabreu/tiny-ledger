package com.teya.tinyledger.integration;

import com.teya.tinyledger.support.OpenApiContract;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * Base class for the HTTP integration tier.
 *
 * <p>Every request made through {@link #api()} is validated against the OpenAPI contract in both
 * directions, so these tests verify two things at once: that the endpoint behaves correctly, and
 * that it behaves as the published contract promises.</p>
 *
 * <p>The seeded demo data is switched off in the test profile, so each test starts from an empty
 * ledger and creates exactly the accounts it needs.</p>
 *
 * <p>Subclasses carry {@code @QuarkusTest} themselves rather than inheriting it: Quarkus
 * registers the annotated class as a CDI bean, and an abstract base class cannot be one.</p>
 */
abstract class AbstractContractTest {

    /**
     * Starts a contract-validated request.
     *
     * <p>Built per call rather than once in a shared specification, so that it always picks up
     * the port Quarkus assigned to the test instance.</p>
     *
     * @return a request specification bound to the running application
     */
    protected static RequestSpecification api() {
        return given()
                .filter(OpenApiContract.validationFilter())
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    /**
     * Starts a request that deliberately violates the contract, bypassing validation.
     *
     * <p>Needed for the handful of tests that check how the server reacts to input the
     * contract itself forbids &mdash; the validating filter would reject those requests before
     * they ever reached the application, which would prove nothing about the server.</p>
     *
     * @return an unvalidated request specification bound to the running application
     */
    protected static RequestSpecification rawApi() {
        return given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    /**
     * Opens an account through the API and returns its identifier.
     *
     * @param ownerName      the account holder
     * @param currency       ISO 4217 code
     * @param overdraftLimit the agreed allowance, as a decimal string
     * @return the new account's identifier
     */
    protected static String openAccount(String ownerName, String currency, String overdraftLimit) {
        return api()
                .body("""
                        {"ownerName":"%s","currency":"%s","overdraftLimit":"%s"}
                        """.formatted(ownerName, currency, overdraftLimit))
                .when().post("/api/v1/accounts")
                .then().statusCode(201)
                .extract().path("id");
    }

    /**
     * Records a movement through the API.
     *
     * @param accountId the account to move money on
     * @param type      {@code DEPOSIT} or {@code WITHDRAWAL}
     * @param amount    the amount, as a decimal string
     * @param currency  ISO 4217 code
     * @param reference an optional note, may be {@code null}
     * @return the raw response for further assertions
     */
    protected static io.restassured.response.Response record(
            String accountId, String type, String amount, String currency, String reference) {
        String body = reference == null
                ? """
                {"type":"%s","amount":"%s","currency":"%s"}
                """.formatted(type, amount, currency)
                : """
                {"type":"%s","amount":"%s","currency":"%s","reference":"%s"}
                """.formatted(type, amount, currency, reference);

        return api().body(body).when().post("/api/v1/accounts/{accountId}/transactions", accountId);
    }
}
