package com.teya.tinyledger.integration;

import com.teya.tinyledger.support.OpenApiContract;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.time.Instant;

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
    /**
     * Records a movement through the API, timed as having happened now and under a fresh,
     * randomly generated idempotency key.
     *
     * <p>Used by every test that is not specifically about idempotency or timing: each call is a
     * genuinely distinct movement, so a fresh key per call is the correct behaviour and keeps
     * those tests free of unrelated detail.</p>
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
        return record(accountId, type, amount, currency, reference, Instant.now(), java.util.UUID.randomUUID().toString());
    }

    /**
     * Records a movement through the API with an explicit client event time and a random idempotency key.
     *
     * @param accountId  the account to move money on
     * @param type       {@code DEPOSIT} or {@code WITHDRAWAL}
     * @param amount     the amount, as a decimal string
     * @param currency   ISO 4217 code
     * @param reference  an optional note, may be {@code null}
     * @param occurredAt the client's event time
     * @return the raw response for further assertions
     */
    protected static io.restassured.response.Response record(
            String accountId, String type, String amount, String currency, String reference, Instant occurredAt) {
        return record(accountId, type, amount, currency, reference, occurredAt, java.util.UUID.randomUUID().toString());
    }

    /**
     * Records a movement through the API under the given idempotency key, timed as having happened now.
     *
     * @param accountId      the account to move money on
     * @param type           {@code DEPOSIT} or {@code WITHDRAWAL}
     * @param amount         the amount, as a decimal string
     * @param currency       ISO 4217 code
     * @param reference      an optional note, may be {@code null}
     * @param idempotencyKey the value of the {@code Idempotency-Key} header
     * @return the raw response for further assertions
     */
    protected static io.restassured.response.Response record(
            String accountId, String type, String amount, String currency, String reference,
            String idempotencyKey) {
        return record(accountId, type, amount, currency, reference, Instant.now(), idempotencyKey);
    }

    /**
     * Records a movement through the API with explicit client event time and explicit idempotency key.
     *
     * @param accountId      the account to move money on
     * @param type           {@code DEPOSIT} or {@code WITHDRAWAL}
     * @param amount         the amount, as a decimal string
     * @param currency       ISO 4217 code
     * @param reference      an optional note, may be {@code null}
     * @param occurredAt     the client's event time
     * @param idempotencyKey the value of the {@code Idempotency-Key} header
     * @return the raw response for further assertions
     */
    protected static io.restassured.response.Response record(
            String accountId, String type, String amount, String currency, String reference,
            Instant occurredAt, String idempotencyKey) {
        String body = reference == null
                ? """
                {"type":"%s","amount":"%s","currency":"%s","occurredAt":"%s"}
                """.formatted(type, amount, currency, occurredAt)
                : """
                {"type":"%s","amount":"%s","currency":"%s","reference":"%s","occurredAt":"%s"}
                """.formatted(type, amount, currency, reference, occurredAt);

        return api()
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .when().post("/api/v1/accounts/{accountId}/transactions", accountId);
    }

    /**
     * Records a movement through the API with no {@code Idempotency-Key} header at all.
     *
     * <p>Uses {@link #rawApi()} since the header is mandatory in the contract, so a
     * contract-validating request would refuse to even send this one.</p>
     *
     * @param accountId the account to move money on
     * @param type      {@code DEPOSIT} or {@code WITHDRAWAL}
     * @param amount    the amount, as a decimal string
     * @param currency  ISO 4217 code
     * @return the raw response for further assertions
     */
    protected static io.restassured.response.Response recordWithoutIdempotencyKey(
            String accountId, String type, String amount, String currency) {
        String body = """
                {"type":"%s","amount":"%s","currency":"%s","occurredAt":"%s"}
                """.formatted(type, amount, currency, Instant.now());

        return rawApi().body(body).when().post("/api/v1/accounts/{accountId}/transactions", accountId);
    }

    /**
     * Records a movement without supplying {@code occurredAt}, bypassing contract validation.
     *
     * <p>The contract marks the field required, so the validating filter would block the
     * request before it reached the server; this proves the server enforces it too.</p>
     *
     * @param accountId the account to move money on
     * @param type      {@code DEPOSIT} or {@code WITHDRAWAL}
     * @param amount    the amount, as a decimal string
     * @param currency  ISO 4217 code
     * @return the raw response for further assertions
     */
    protected static io.restassured.response.Response recordWithoutOccurredAt(
            String accountId, String type, String amount, String currency) {
        return rawApi()
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .body("""
                        {"type":"%s","amount":"%s","currency":"%s"}
                        """.formatted(type, amount, currency))
                .when().post("/api/v1/accounts/{accountId}/transactions", accountId);
    }
}
