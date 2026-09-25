package com.teya.tinyledger.integration;

import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for the transaction endpoints, validated against the OpenAPI contract.
 */
@QuarkusTest
@Tag("integration")
@DisplayName("Transaction API")
class TransactionApiTest extends AbstractContractTest {

    @Test
    @DisplayName("a deposit is recorded and returns 201 with the resulting balance")
    void recordTransaction_validDeposit_returnsCreatedWithUpdatedBalance() {
        String accountId = openAccount("Deposit Owner", "EUR", "0.00");

        record(accountId, "DEPOSIT", "100.00", "EUR", "Salary")
                .then()
                .statusCode(201)
                .header("Location", containsString("/transactions"))
                .body("type", equalTo("DEPOSIT"))
                .body("amount", equalTo("100.00"))
                .body("currency", equalTo("EUR"))
                .body("availableBalanceAfter", equalTo("100.00"))
                .body("reference", equalTo("Salary"));
    }

    @Test
    @DisplayName("a withdrawal is recorded and reduces the balance")
    void recordTransaction_validWithdrawal_returnsCreatedAndDecreasesBalance() {
        String accountId = openAccount("Withdrawal Owner", "EUR", "0.00");
        record(accountId, "DEPOSIT", "100.00", "EUR", null).then().statusCode(201);

        record(accountId, "WITHDRAWAL", "30.00", "EUR", "Rent")
                .then()
                .statusCode(201)
                .body("availableBalanceAfter", equalTo("70.00"));
    }

    @Test
    @DisplayName("the reference is optional")
    void recordTransaction_omittedReference_returnsCreatedWithNullReference() {
        String accountId = openAccount("No Reference", "EUR", "0.00");

        record(accountId, "DEPOSIT", "10.00", "EUR", null)
                .then()
                .statusCode(201)
                .body("reference", nullValue());
    }

    @Test
    @DisplayName("a withdrawal within the overdraft allowance is accepted")
    void recordTransaction_withdrawalWithinOverdraftLimit_returnsCreated() {
        String accountId = openAccount("Overdraft Owner", "EUR", "100.00");

        record(accountId, "WITHDRAWAL", "100.00", "EUR", null)
                .then()
                .statusCode(201)
                .body("availableBalanceAfter", equalTo("-100.00"));
    }

    @Test
    @DisplayName("a withdrawal beyond the overdraft allowance is refused with 422")
    void recordTransaction_withdrawalExceedingOverdraftLimit_returnsUnprocessableEntity() {
        String accountId = openAccount("Overdraft Owner", "EUR", "100.00");

        record(accountId, "WITHDRAWAL", "100.01", "EUR", null)
                .then()
                .statusCode(422)
                .body("code", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("a refused withdrawal leaves no trace in the history")
    void recordTransaction_refusedWithdrawal_leavesHistoryUntouched() {
        String accountId = openAccount("Clean History", "EUR", "0.00");
        record(accountId, "DEPOSIT", "10.00", "EUR", null).then().statusCode(201);

        record(accountId, "WITHDRAWAL", "50.00", "EUR", null).then().statusCode(422);

        api().when().get("/api/v1/accounts/{accountId}/transactions", accountId)
                .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("transactions", hasSize(1));
    }

    @Test
    @DisplayName("a movement in another currency is refused with 422")
    void recordTransaction_mismatchedCurrency_returnsUnprocessableEntity() {
        String accountId = openAccount("Euro Only", "EUR", "0.00");

        record(accountId, "DEPOSIT", "10.00", "GBP", null)
                .then()
                .statusCode(422)
                .body("code", equalTo("CURRENCY_MISMATCH"));
    }

    @Test
    @DisplayName("a non-positive amount is refused with 400")
    void recordTransaction_zeroAmount_returnsBadRequest() {
        String accountId = openAccount("Amount Guard", "EUR", "0.00");

        record(accountId, "DEPOSIT", "0.00", "EUR", null)
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an amount more precise than the currency allows is refused with 400")
    void recordTransaction_amountMorePreciseThanMinorUnits_returnsBadRequest() {
        String accountId = openAccount("Precision Guard", "EUR", "0.00");

        record(accountId, "DEPOSIT", "10.001", "EUR", null)
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("a movement on an unknown account is refused with 404")
    void recordTransaction_unknownAccountId_returnsNotFound() {
        record("11111111-1111-1111-1111-111111111111", "DEPOSIT", "10.00", "EUR", null)
                .then()
                .statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("history is returned most recent first")
    void getHistory_multipleMovements_returnsMostRecentFirst() {
        String accountId = openAccount("History Owner", "EUR", "0.00");
        record(accountId, "DEPOSIT", "10.00", "EUR", "first").then().statusCode(201);
        record(accountId, "DEPOSIT", "20.00", "EUR", "second").then().statusCode(201);
        record(accountId, "DEPOSIT", "30.00", "EUR", "third").then().statusCode(201);

        api().when().get("/api/v1/accounts/{accountId}/transactions", accountId)
                .then()
                .statusCode(200)
                .body("accountId", equalTo(accountId))
                .body("total", equalTo(3))
                .body("transactions.reference", contains("third", "second", "first"));
    }

    @Test
    @DisplayName("history honours limit and offset")
    void getHistory_withLimitAndOffset_returnsRequestedPage() {
        String accountId = openAccount("Paging Owner", "EUR", "0.00");
        for (int i = 1; i <= 5; i++) {
            record(accountId, "DEPOSIT", i + ".00", "EUR", "movement-" + i).then().statusCode(201);
        }

        api().queryParam("limit", 2).queryParam("offset", 1)
                .when().get("/api/v1/accounts/{accountId}/transactions", accountId)
                .then()
                .statusCode(200)
                .body("limit", equalTo(2))
                .body("offset", equalTo(1))
                .body("total", equalTo(5))
                .body("transactions.reference", contains("movement-4", "movement-3"));
    }

    @Test
    @DisplayName("history of an account with no movements is an empty page")
    void getHistory_accountWithNoMovements_returnsEmptyPage() {
        String accountId = openAccount("Empty History", "EUR", "0.00");

        api().when().get("/api/v1/accounts/{accountId}/transactions", accountId)
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("transactions", Matchers.empty());
    }

    @Test
    @DisplayName("a page size beyond the supported maximum is refused with 400")
    void getHistory_limitExceedingMaximum_returnsBadRequest() {
        String accountId = openAccount("Paging Guard", "EUR", "0.00");

        rawApi().queryParam("limit", 1000)
                .when().get("/api/v1/accounts/{accountId}/transactions", accountId)
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a non-numeric page size is refused with 400 rather than 404")
    void getHistory_nonNumericLimit_returnsBadRequest() {
        String accountId = openAccount("Paging Guard", "EUR", "0.00");

        rawApi().queryParam("limit", "abc")
                .when().get("/api/v1/accounts/{accountId}/transactions", accountId)
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"))
                .body("message", containsString("limit"));
    }

    @Test
    @DisplayName("the client's event time is echoed back alongside the ledger's own")
    void recordTransaction_withOccurredAt_echoesItBackSeparatelyFromRecordedAt() {
        String accountId = openAccount("Event Time Owner", "EUR", "0.00");
        Instant clientTime = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);

        record(accountId, "DEPOSIT", "100.00", "EUR", "Backdated salary", clientTime)
                .then()
                .statusCode(201)
                .body("occurredAt", equalTo(clientTime.toString()))
                .body("recordedAt", Matchers.not(equalTo(clientTime.toString())));
    }

    @Test
    @DisplayName("a movement without an event time is refused with 400")
    void recordTransaction_missingOccurredAt_returnsBadRequest() {
        String accountId = openAccount("Missing Time Owner", "EUR", "0.00");

        recordWithoutOccurredAt(accountId, "DEPOSIT", "100.00", "EUR")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an event time older than the accepted window is refused with 400")
    void recordTransaction_occurredAtBeyondPastDriftWindow_returnsBadRequest() {
        String accountId = openAccount("Stale Clock Owner", "EUR", "0.00");
        Instant tooOld = Instant.now().minus(Duration.ofDays(3));

        record(accountId, "DEPOSIT", "100.00", "EUR", null, tooOld)
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"))
                .body("message", containsString("occurredAt"));
    }

    @Test
    @DisplayName("an event time in the future is refused with 400")
    void recordTransaction_occurredAtBeyondFutureDriftWindow_returnsBadRequest() {
        String accountId = openAccount("Fast Clock Owner", "EUR", "0.00");
        Instant future = Instant.now().plus(Duration.ofHours(1));

        record(accountId, "DEPOSIT", "100.00", "EUR", null, future)
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"))
                .body("message", containsString("occurredAt"));
    }

    @Test
    @DisplayName("a rejected event time leaves the balance and history untouched")
    void recordTransaction_rejectedForDrift_doesNotRecordAnything() {
        String accountId = openAccount("Untouched Owner", "EUR", "0.00");
        record(accountId, "DEPOSIT", "100.00", "EUR", null).then().statusCode(201);

        record(accountId, "DEPOSIT", "50.00", "EUR", null, Instant.now().minus(Duration.ofDays(3)))
                .then().statusCode(400);

        rawApi().when().get("/api/v1/accounts/{accountId}/balance", accountId)
                .then()
                .statusCode(200)
                .body("availableBalance", equalTo("100.00"))
                .body("transactionCount", equalTo(1));
    }

    @Test
    @DisplayName("history stays ordered by when the ledger recorded movements, not by the client's clock")
    void getHistory_outOfOrderOccurredAt_ordersByRecordingOrderNotEventTime() {
        String accountId = openAccount("Ordering Owner", "EUR", "0.00");
        Instant now = Instant.now();

        // Client times run backwards while the recording order runs forwards.
        record(accountId, "DEPOSIT", "10.00", "EUR", "first", now.minus(Duration.ofHours(1)))
                .then().statusCode(201);
        record(accountId, "DEPOSIT", "20.00", "EUR", "second", now.minus(Duration.ofHours(10)))
                .then().statusCode(201);
        record(accountId, "DEPOSIT", "30.00", "EUR", "third", now.minus(Duration.ofHours(20)))
                .then().statusCode(201);

        api().when().get("/api/v1/accounts/{accountId}/transactions", accountId)
                .then()
                .statusCode(200)
                .body("transactions.reference", contains("third", "second", "first"))
                .body("transactions.availableBalanceAfter", contains("60.00", "30.00", "10.00"));
    }

    @Test
    @DisplayName("a request with no Idempotency-Key header is refused with 400")
    void recordTransaction_missingIdempotencyKeyHeader_returnsBadRequest() {
        String accountId = openAccount("Idempotency Guard", "EUR", "0.00");

        recordWithoutIdempotencyKey(accountId, "DEPOSIT", "10.00", "EUR")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"))
                // Spelled out rather than referencing TransactionResource's constant: this is
                // the wire-facing name, so renaming the constant must fail here, not pass.
                .body("message", equalTo("Header 'Idempotency-Key' is required"));
    }

    @Test
    @DisplayName("replaying the same Idempotency-Key with the same body returns the original 201 again")
    void recordTransaction_replayedIdempotencyKey_returnsOriginalResponseWithoutDoubleRecording() {
        String accountId = openAccount("Idempotent Retry", "EUR", "0.00");
        String key = "retry-key-1";

        io.restassured.response.Response first =
                record(accountId, "DEPOSIT", "25.00", "EUR", "Salary", key);
        first.then().statusCode(201);
        String firstTransactionId = first.jsonPath().getString("id");

        record(accountId, "DEPOSIT", "25.00", "EUR", "Salary", key)
                .then()
                .statusCode(201)
                .body("id", equalTo(firstTransactionId))
                .body("availableBalanceAfter", equalTo("25.00"));

        api().when().get("/api/v1/accounts/{accountId}/transactions", accountId)
                .then()
                .statusCode(200)
                .body("total", equalTo(1));
    }

    @Test
    @DisplayName("reusing an Idempotency-Key for a different amount is refused with 409")
    void recordTransaction_idempotencyKeyReusedForDifferentAmount_returnsConflict() {
        String accountId = openAccount("Idempotency Conflict", "EUR", "0.00");
        String key = "conflict-key-1";
        record(accountId, "DEPOSIT", "25.00", "EUR", null, key).then().statusCode(201);

        record(accountId, "DEPOSIT", "30.00", "EUR", null, key)
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("an Idempotency-Key refused for insufficient funds is not consumed and a later retry can succeed")
    void recordTransaction_idempotencyKeyRefusedForInsufficientFunds_canBeRetriedAfterTopUp() {
        String accountId = openAccount("Idempotency Retry After Top-Up", "EUR", "0.00");
        String key = "retry-after-top-up";

        record(accountId, "WITHDRAWAL", "10.00", "EUR", null, key).then().statusCode(422);
        record(accountId, "DEPOSIT", "10.00", "EUR", null).then().statusCode(201);

        record(accountId, "WITHDRAWAL", "10.00", "EUR", null, key)
                .then()
                .statusCode(201)
                .body("availableBalanceAfter", equalTo("0.00"));
    }
}
