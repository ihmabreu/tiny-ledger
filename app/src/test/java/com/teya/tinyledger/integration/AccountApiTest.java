package com.teya.tinyledger.integration;

import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the account endpoints, validated against the OpenAPI contract.
 */
@QuarkusTest
@Tag("integration")
@DisplayName("Account API")
class AccountApiTest extends AbstractContractTest {

    @Test
    @DisplayName("opening an account returns 201 with a Location header")
    void openAccount_validPayload_returnsCreatedWithLocationAndBalances() {
        api()
                .body("""
                        {"ownerName":"Ada Lovelace","currency":"EUR","overdraftLimit":"250.00"}
                        """)
                .when().post("/api/v1/accounts")
                .then()
                .statusCode(201)
                .header("Location", containsString("/api/v1/accounts/"))
                .body("id", notNullValue())
                .body("ownerName", equalTo("Ada Lovelace"))
                .body("currency", equalTo("EUR"))
                .body("overdraftLimit", equalTo("250.00"))
                .body("availableBalance", equalTo("0.00"))
                .body("accountBalance", equalTo("250.00"));
    }

    @Test
    @DisplayName("the overdraft allowance defaults to zero when omitted")
    void openAccount_omittedOverdraftLimit_defaultsToZero() {
        api()
                .body("""
                        {"ownerName":"Alan Turing","currency":"EUR"}
                        """)
                .when().post("/api/v1/accounts")
                .then()
                .statusCode(201)
                .body("overdraftLimit", equalTo("0.00"));
    }

    @Test
    @DisplayName("monetary amounts are serialised as strings, never as JSON numbers")
    void getAccount_existingAccount_serialisesAmountsAsStrings() {
        String accountId = openAccount("Money Format", "EUR", "0.00");

        api().when().get("/api/v1/accounts/{accountId}", accountId)
                .then()
                .statusCode(200)
                .body("availableBalance", Matchers.instanceOf(String.class))
                .body("availableBalance", matchesRegex("-?\\d+\\.\\d{2}"));
    }

    @Test
    @DisplayName("an opened account can be read back")
    void getAccount_existingAccount_returnsAccountDetails() {
        String accountId = openAccount("Grace Hopper", "GBP", "500.00");

        api().when().get("/api/v1/accounts/{accountId}", accountId)
                .then()
                .statusCode(200)
                .body("id", equalTo(accountId))
                .body("currency", equalTo("GBP"))
                .body("openedAt", notNullValue());
    }

    @Test
    @DisplayName("every opened account appears in the listing")
    void listAccounts_existingAccount_includesInListing() {
        String accountId = openAccount("Listed Owner", "EUR", "0.00");

        api().when().get("/api/v1/accounts")
                .then()
                .statusCode(200)
                .body("id", Matchers.hasItem(accountId));
    }

    @Test
    @DisplayName("a blank owner name is rejected with 400 and a field-level message")
    void openAccount_blankOwnerName_returnsBadRequestWithValidationDetails() {
        api()
                .body("""
                        {"ownerName":"   ","currency":"EUR"}
                        """)
                .when().post("/api/v1/accounts")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"))
                .body("details", Matchers.hasItem(containsString("ownerName")));
    }

    @Test
    @DisplayName("an unknown currency code is rejected with 400")
    void openAccount_invalidCurrencyCode_returnsBadRequest() {
        api()
                .body("""
                        {"ownerName":"Ada","currency":"ZZZ"}
                        """)
                .when().post("/api/v1/accounts")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a negative overdraft allowance is rejected with 400")
    void openAccount_negativeOverdraftLimit_returnsBadRequest() {
        api()
                .body("""
                        {"ownerName":"Ada","currency":"EUR","overdraftLimit":"-1.00"}
                        """)
                .when().post("/api/v1/accounts")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("an unknown account returns 404 with a machine-readable code")
    void getAccount_unknownAccountId_returnsNotFoundWithErrorCode() {
        api().when().get("/api/v1/accounts/{accountId}", "11111111-1111-1111-1111-111111111111")
                .then()
                .statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("the balance endpoint reports both balance figures and the allowance")
    void getBalance_existingAccountWithTransactions_returnsBothBalancesAndOverdraft() {
        String accountId = openAccount("Balance Owner", "EUR", "100.00");
        record(accountId, "DEPOSIT", "40.00", "EUR", "Salary").then().statusCode(201);

        api().when().get("/api/v1/accounts/{accountId}/balance", accountId)
                .then()
                .statusCode(200)
                .body("accountId", equalTo(accountId))
                .body("availableBalance", equalTo("40.00"))
                .body("accountBalance", equalTo("140.00"))
                .body("overdraftLimit", equalTo("100.00"))
                .body("transactionCount", equalTo(1));
    }
}
