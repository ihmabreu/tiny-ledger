package com.teya.tinyledger.integration;

import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
}
