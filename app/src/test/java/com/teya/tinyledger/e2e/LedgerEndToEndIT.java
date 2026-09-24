package com.teya.tinyledger.e2e;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End-to-end tests against the packaged application.
 *
 * <p>Unlike the other tiers, these run against the built artifact started as a separate
 * process, with no test profile and no test-only wiring. That is the point: they are the only
 * tests that can catch packaging problems &mdash; a resource missing from the jar, a
 * configuration value that only works in dev mode, a bean that fails to start outside the test
 * classpath &mdash; which every in-process test would sail straight past.</p>
 *
 * <p>Because there is no test profile, the demo data seeder <em>is</em> active here, so these
 * tests also verify that a freshly deployed instance comes up genuinely usable.</p>
 */
@QuarkusIntegrationTest
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Packaged application")
class LedgerEndToEndIT {

    @Test
    @Order(1)
    @DisplayName("starts up with the demo data already seeded")
    void bootstrap_withSeededProfile_startsUpWithPreloadedAccounts() {
        given().accept(ContentType.JSON)
                .when().get("/api/v1/accounts")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(3))
                .body("ownerName", hasItem("Ada Lovelace"));
    }

    @Test
    @Order(2)
    @DisplayName("publishes the OpenAPI contract and Swagger UI")
    void bootstrap_defaultConfiguration_publishesOpenApiContractAndSwaggerUi() {
        given().when().get("/q/openapi")
                .then().statusCode(200).body(containsString("Tiny Ledger API"));

        given().when().get("/q/swagger-ui")
                .then().statusCode(200);
    }

    @Test
    @Order(3)
    @DisplayName("supports the full account journey: open, pay in, take out, review")
    void accountLifecycle_openDepositWithdrawAndHistory_completesEndToEndJourneySuccessfully() {
        String accountId = given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body("""
                        {"ownerName":"End To End","currency":"EUR","overdraftLimit":"200.00"}
                        """)
                .when().post("/api/v1/accounts")
                .then()
                .statusCode(201)
                .header("Location", containsString("/api/v1/accounts/"))
                .body("availableBalance", equalTo("0.00"))
                .body("accountBalance", equalTo("200.00"))
                .extract().path("id");

        given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body("""
                        {"type":"DEPOSIT","amount":"1000.00","currency":"EUR","reference":"Salary"}
                        """)
                .when().post("/api/v1/accounts/{id}/transactions", accountId)
                .then().statusCode(201)
                .body("availableBalanceAfter", equalTo("1000.00"));

        given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body("""
                        {"type":"WITHDRAWAL","amount":"1150.00","currency":"EUR","reference":"Car repair"}
                        """)
                .when().post("/api/v1/accounts/{id}/transactions", accountId)
                .then().statusCode(201)
                .body("availableBalanceAfter", equalTo("-150.00"));

        given().accept(ContentType.JSON)
                .when().get("/api/v1/accounts/{id}/balance", accountId)
                .then()
                .statusCode(200)
                .body("availableBalance", equalTo("-150.00"))
                .body("accountBalance", equalTo("50.00"))
                .body("overdraftLimit", equalTo("200.00"))
                .body("transactionCount", equalTo(2))
                .body("calculatedAt", notNullValue());

        given().accept(ContentType.JSON)
                .when().get("/api/v1/accounts/{id}/transactions", accountId)
                .then()
                .statusCode(200)
                .body("total", equalTo(2))
                .body("transactions", hasSize(2))
                .body("transactions.reference", contains("Car repair", "Salary"));
    }

    @Test
    @Order(4)
    @DisplayName("refuses to let an account spend past its overdraft allowance")
    void recordTransaction_withdrawalExceedingAllowance_enforcesLimitAndRefusesWith422() {
        String accountId = given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body("""
                        {"ownerName":"Overdraft Journey","currency":"EUR","overdraftLimit":"10.00"}
                        """)
                .when().post("/api/v1/accounts")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body("""
                        {"type":"WITHDRAWAL","amount":"10.01","currency":"EUR"}
                        """)
                .when().post("/api/v1/accounts/{id}/transactions", accountId)
                .then()
                .statusCode(422)
                .body("code", equalTo("INSUFFICIENT_FUNDS"));

        given().accept(ContentType.JSON)
                .when().get("/api/v1/accounts/{id}/transactions", accountId)
                .then().body("total", equalTo(0));
    }

    @Test
    @Order(5)
    @DisplayName("reports an unknown account as 404 with a machine-readable code")
    void getAccount_unknownAccountId_returnsNotFoundWithMachineReadableCode() {
        given().accept(ContentType.JSON)
                .when().get("/api/v1/accounts/{id}", "11111111-1111-1111-1111-111111111111")
                .then()
                .statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
    }
}
