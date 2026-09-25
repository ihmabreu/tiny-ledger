package com.teya.tinyledger.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Instant;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Building blocks shared by the load simulations.
 *
 * <p>Keeping the protocol, the request chains and the feeders in one place means each
 * simulation file contains only what makes it distinct &mdash; its injection profile and its
 * assertions &mdash; which is what the reader actually needs to compare.</p>
 */
final class LedgerLoadSupport {

    /** System property naming the instance under test. */
    static final String BASE_URL_PROPERTY = "tinyledger.baseUrl";

    private LedgerLoadSupport() {
    }

    /**
     * Builds the HTTP protocol configuration.
     *
     * @return the protocol pointed at the instance under test
     */
    static HttpProtocolBuilder protocol() {
        return http
                .baseUrl(System.getProperty(BASE_URL_PROPERTY, "http://localhost:8080"))
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .shareConnections()
                .userAgentHeader("tiny-ledger-gatling");
    }

    /**
     * Opens an account and stores its identifier in the session under {@code accountId}.
     *
     * @return the request chain
     */
    static ChainBuilder openAccount() {
        return exec(http("Open account")
                .post("/api/v1/accounts")
                .body(StringBody(session -> """
                        {"ownerName":"Load Test #%s","currency":"EUR","overdraftLimit":"1000000.00"}
                        """.formatted(session.userId())))
                .check(status().is(201))
                .check(jsonPath("$.id").saveAs("accountId")));
    }

    /**
     * Pays a small amount into the account held in the session.
     *
     * <p>{@code occurredAt} is built per request rather than baked into a constant body, so the
     * simulation sends the same shape of payload a real client would. A value captured once at
     * build time would still pass validation, which is exactly why it would be a misleading
     * thing to write.</p>
     *
     * @return the request chain
     */
    static ChainBuilder deposit() {
        return exec(http("Record deposit")
                .post("/api/v1/accounts/#{accountId}/transactions")
                .header("Idempotency-Key", session -> java.util.UUID.randomUUID().toString())
                .body(StringBody(session -> """
                        {"type":"DEPOSIT","amount":"10.00","currency":"EUR","reference":"Load test deposit","occurredAt":"%s"}
                        """.formatted(Instant.now())))
                .check(status().is(201)));
    }

    /**
     * Takes a small amount out of the account held in the session.
     *
     * @return the request chain
     */
    static ChainBuilder withdraw() {
        return exec(http("Record withdrawal")
                .post("/api/v1/accounts/#{accountId}/transactions")
                .header("Idempotency-Key", session -> java.util.UUID.randomUUID().toString())
                .body(StringBody(session -> """
                        {"type":"WITHDRAWAL","amount":"5.00","currency":"EUR","reference":"Load test withdrawal","occurredAt":"%s"}
                        """.formatted(Instant.now())))
                .check(status().is(201)));
    }

    /**
     * Reads the balance of the account held in the session.
     *
     * @return the request chain
     */
    static ChainBuilder readBalance() {
        return exec(http("Read balance")
                .get("/api/v1/accounts/#{accountId}/balance")
                .check(status().is(200)));
    }

    /**
     * Reads the first page of history for the account held in the session.
     *
     * @return the request chain
     */
    static ChainBuilder readHistory() {
        return exec(http("Read history")
                .get("/api/v1/accounts/#{accountId}/transactions?limit=20")
                .check(status().is(200)));
    }

    /**
     * A realistic mix: money moves a few times, and is then reviewed.
     *
     * <p>Reads outnumber writes because that is how a ledger is actually used &mdash; a load
     * profile of pure writes would measure something no real client does.</p>
     *
     * @param cycles how many times the mix is repeated per virtual user
     * @return the scenario
     */
    static ScenarioBuilder mixedTrafficScenario(int cycles) {
        return scenario("Mixed ledger traffic")
                .exec(openAccount())
                .repeat(cycles).on(
                        exec(deposit())
                                .exec(readBalance())
                                .exec(withdraw())
                                .exec(readBalance())
                                .exec(readHistory()));
    }

}
