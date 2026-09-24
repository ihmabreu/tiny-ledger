package com.teya.tinyledger.bdd;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions translating the Gherkin scenarios into HTTP calls.
 *
 * <p>All knowledge of transport, payload shapes and status codes is confined here, which is
 * what allows the feature files to stay in the language of the business. If the API changed
 * shape, only this class would need to follow.</p>
 */
public class LedgerSteps {

    private final Map<String, String> accountIdsByOwner = new HashMap<>();

    private Response lastResponse;
    private Response lastHistoryResponse;

    /**
     * Clears the per-scenario state so scenarios cannot influence one another.
     */
    @Before
    public void resetScenarioState() {
        accountIdsByOwner.clear();
        lastResponse = null;
        lastHistoryResponse = null;
    }

    private static RequestSpecification api() {
        return given().contentType(ContentType.JSON).accept(ContentType.JSON);
    }

    private String accountId(String owner) {
        String accountId = accountIdsByOwner.get(owner);
        assertThat(accountId)
                .as("account \"%s\" has not been opened in this scenario", owner)
                .isNotNull();
        return accountId;
    }

    // ----------------------------------------------------------------- given

    /**
     * Opens an account for the named owner.
     *
     * @param owner          the account holder's name, used to refer to the account later
     * @param currency       ISO 4217 code
     * @param overdraftLimit the agreed allowance
     */
    @Given("^an account \"([^\"]*)\" in ([A-Z]{3}) with an overdraft allowance of (-?[\\d.]+)$")
    public void anAccount(String owner, String currency, String overdraftLimit) {
        String accountId = api()
                .body("""
                        {"ownerName":"%s","currency":"%s","overdraftLimit":"%s"}
                        """.formatted(owner, currency, overdraftLimit))
                .when().post("/api/v1/accounts")
                .then().statusCode(201)
                .extract().path("id");

        accountIdsByOwner.put(owner, accountId);
    }

    /**
     * Records a deposit that sets up the scenario rather than being the behaviour under test.
     *
     * @param owner    the account holder
     * @param amount   the amount paid in
     * @param currency ISO 4217 code
     */
    @Given("^\"([^\"]*)\" has already received a deposit of ([\\d.]+) ([A-Z]{3})$")
    public void hasAlreadyReceivedADeposit(String owner, String amount, String currency) {
        move(owner, "DEPOSIT", amount, currency, null).then().statusCode(201);
    }

    /**
     * Records a run of movements described by a Gherkin table.
     *
     * @param owner     the account holder
     * @param movements a table of {@code type}, {@code amount} and {@code reference} columns
     */
    @Given("^the following movements have been recorded for \"([^\"]*)\":$")
    public void theFollowingMovements(String owner, DataTable movements) {
        String currency = api().when().get("/api/v1/accounts/{id}", accountId(owner))
                .then().statusCode(200)
                .extract().path("currency");

        for (Map<String, String> row : movements.asMaps()) {
            move(owner, row.get("type"), row.get("amount"), currency, row.get("reference"))
                    .then().statusCode(201);
        }
    }

    // ------------------------------------------------------------------ when

    /**
     * Pays money into an account.
     *
     * @param amount   the amount
     * @param currency ISO 4217 code
     * @param owner    the account holder
     */
    @When("^I deposit (-?[\\d.]+) ([A-Z]{3}) into \"([^\"]*)\"$")
    public void iDeposit(String amount, String currency, String owner) {
        lastResponse = move(owner, "DEPOSIT", amount, currency, null);
    }

    /**
     * Pays money into an account, quoting a reference.
     *
     * @param amount    the amount
     * @param currency  ISO 4217 code
     * @param owner     the account holder
     * @param reference the free-text note
     */
    @When("^I deposit (-?[\\d.]+) ([A-Z]{3}) into \"([^\"]*)\" with the reference \"([^\"]*)\"$")
    public void iDepositWithReference(String amount, String currency, String owner, String reference) {
        lastResponse = move(owner, "DEPOSIT", amount, currency, reference);
    }

    /**
     * Takes money out of an account.
     *
     * @param amount   the amount
     * @param currency ISO 4217 code
     * @param owner    the account holder
     */
    @When("^I withdraw (-?[\\d.]+) ([A-Z]{3}) from \"([^\"]*)\"$")
    public void iWithdraw(String amount, String currency, String owner) {
        lastResponse = move(owner, "WITHDRAWAL", amount, currency, null);
    }

    /**
     * Pays money in, claiming it happened some hours ago at the caller's end.
     *
     * @param amount    the amount
     * @param currency  ISO 4217 code
     * @param owner     the account holder
     * @param reference the free-text note
     * @param hoursAgo  how long ago the caller says the movement happened
     */
    @When("^I deposit (-?[\\d.]+) ([A-Z]{3}) into \"([^\"]*)\" with the reference \"([^\"]*)\", claiming it happened (\\d+) hours ago$")
    public void iDepositClaimingItHappenedHoursAgo(String amount, String currency, String owner,
                                                   String reference, int hoursAgo) {
        lastResponse = move(owner, "DEPOSIT", amount, currency, reference,
                Instant.now().minus(Duration.ofHours(hoursAgo)));
    }

    /**
     * Pays money in, claiming it happened implausibly long ago.
     *
     * @param amount   the amount
     * @param currency ISO 4217 code
     * @param owner    the account holder
     * @param daysAgo  how long ago the caller says the movement happened
     */
    @When("^I deposit (-?[\\d.]+) ([A-Z]{3}) into \"([^\"]*)\", claiming it happened (\\d+) days ago$")
    public void iDepositClaimingItHappenedDaysAgo(String amount, String currency, String owner, int daysAgo) {
        lastResponse = move(owner, "DEPOSIT", amount, currency, null,
                Instant.now().minus(Duration.ofDays(daysAgo)));
    }

    /**
     * Pays money in, claiming it will happen in the future.
     *
     * @param amount     the amount
     * @param currency   ISO 4217 code
     * @param owner      the account holder
     * @param hoursAhead how far ahead the caller places the movement
     */
    @When("^I deposit (-?[\\d.]+) ([A-Z]{3}) into \"([^\"]*)\", claiming it will happen in (\\d+) hours$")
    public void iDepositClaimingItHappensInTheFuture(String amount, String currency, String owner, int hoursAhead) {
        lastResponse = move(owner, "DEPOSIT", amount, currency, null,
                Instant.now().plus(Duration.ofHours(hoursAhead)));
    }

    /**
     * Reads the default page of history.
     *
     * @param owner the account holder
     */
    @When("^I view the transaction history of \"([^\"]*)\"$")
    public void iViewTheHistory(String owner) {
        lastHistoryResponse = api()
                .when().get("/api/v1/accounts/{id}/transactions", accountId(owner));
        lastHistoryResponse.then().statusCode(200);
    }

    /**
     * Reads a specific page of history.
     *
     * @param owner  the account holder
     * @param limit  the page size
     * @param offset how many of the most recent movements to skip
     */
    @When("^I view the transaction history of \"([^\"]*)\" with limit (\\d+) and offset (\\d+)$")
    public void iViewTheHistoryPaged(String owner, int limit, int offset) {
        lastHistoryResponse = api()
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .when().get("/api/v1/accounts/{id}/transactions", accountId(owner));
        lastHistoryResponse.then().statusCode(200);
    }

    // ------------------------------------------------------------------ then

    /**
     * Asserts the last deposit was accepted.
     */
    @Then("^the deposit is accepted$")
    public void theDepositIsAccepted() {
        lastResponse.then().statusCode(201).body("type", org.hamcrest.Matchers.equalTo("DEPOSIT"));
    }

    /**
     * Asserts the last withdrawal was accepted.
     */
    @Then("^the withdrawal is accepted$")
    public void theWithdrawalIsAccepted() {
        lastResponse.then().statusCode(201).body("type", org.hamcrest.Matchers.equalTo("WITHDRAWAL"));
    }

    /**
     * Asserts the last withdrawal was refused for want of funds.
     */
    @Then("^the withdrawal is refused because of insufficient funds$")
    public void theWithdrawalIsRefused() {
        lastResponse.then().statusCode(422)
                .body("code", org.hamcrest.Matchers.equalTo("INSUFFICIENT_FUNDS"));
    }

    /**
     * Asserts the last movement was refused because it was in the wrong currency.
     */
    @Then("^the movement is refused because of a currency mismatch$")
    public void theMovementIsRefusedForCurrency() {
        lastResponse.then().statusCode(422)
                .body("code", org.hamcrest.Matchers.equalTo("CURRENCY_MISMATCH"));
    }

    /**
     * Asserts the last movement was rejected as malformed.
     */
    @Then("^the movement is rejected as invalid$")
    public void theMovementIsRejected() {
        lastResponse.then().statusCode(400)
                .body("code", org.hamcrest.Matchers.equalTo("VALIDATION_FAILED"));
    }

    /**
     * Asserts the available balance, that is the sum of the movements.
     *
     * @param owner    the account holder
     * @param expected the expected amount
     * @param currency ISO 4217 code
     */
    @Then("^the available balance of \"([^\"]*)\" is (-?[\\d.]+) ([A-Z]{3})$")
    public void theAvailableBalanceIs(String owner, String expected, String currency) {
        balance(owner).then()
                .body("availableBalance", org.hamcrest.Matchers.equalTo(expected))
                .body("currency", org.hamcrest.Matchers.equalTo(currency));
    }

    /**
     * Asserts the account balance, that is the available balance plus the allowance.
     *
     * @param owner    the account holder
     * @param expected the expected amount
     * @param currency ISO 4217 code
     */
    @Then("^the account balance of \"([^\"]*)\" is (-?[\\d.]+) ([A-Z]{3})$")
    public void theAccountBalanceIs(String owner, String expected, String currency) {
        balance(owner).then()
                .body("accountBalance", org.hamcrest.Matchers.equalTo(expected))
                .body("currency", org.hamcrest.Matchers.equalTo(currency));
    }

    /**
     * Asserts the agreed overdraft allowance.
     *
     * @param owner    the account holder
     * @param expected the expected amount
     * @param currency ISO 4217 code
     */
    @Then("^the overdraft allowance of \"([^\"]*)\" is (-?[\\d.]+) ([A-Z]{3})$")
    public void theOverdraftAllowanceIs(String owner, String expected, String currency) {
        balance(owner).then()
                .body("overdraftLimit", org.hamcrest.Matchers.equalTo(expected))
                .body("currency", org.hamcrest.Matchers.equalTo(currency));
    }

    /**
     * Asserts the account has recorded nothing at all.
     *
     * @param owner the account holder
     */
    @Then("^the transaction history of \"([^\"]*)\" is empty$")
    public void theHistoryIsEmpty(String owner) {
        iViewTheHistory(owner);
        lastHistoryResponse.then()
                .body("total", org.hamcrest.Matchers.equalTo(0))
                .body("transactions", org.hamcrest.Matchers.empty());
    }

    /**
     * Asserts how many movements the last history page returned.
     *
     * @param expected the expected number of movements
     */
    @Then("^the history shows (\\d+) movements$")
    public void theHistoryShows(int expected) {
        lastHistoryResponse.then()
                .body("transactions", org.hamcrest.Matchers.hasSize(expected));
    }

    /**
     * Asserts how many movements the account holds in total.
     *
     * @param expected the expected total
     */
    @Then("^the history reports a total of (\\d+) movements$")
    public void theHistoryTotalIs(int expected) {
        lastHistoryResponse.then().body("total", org.hamcrest.Matchers.equalTo(expected));
    }

    /**
     * Asserts the exact order of the references in the last history page.
     *
     * @param expectedReferences a comma-separated, quoted list in the expected order
     */
    @Then("^the references in the history are (.+)$")
    public void theReferencesAre(String expectedReferences) {
        List<String> expected = java.util.Arrays.stream(expectedReferences.split(","))
                .map(String::strip)
                .map(value -> value.replaceAll("^\"|\"$", ""))
                .toList();

        assertThat(lastHistoryResponse.jsonPath().getList("transactions.reference", String.class))
                .containsExactlyElementsOf(expected);
    }

    /**
     * Asserts the balance carried by the newest movement in the last history page.
     *
     * @param expected the expected amount
     * @param currency ISO 4217 code
     */
    @Then("^the most recent movement shows a resulting balance of (-?[\\d.]+) ([A-Z]{3})$")
    public void theMostRecentMovementShows(String expected, String currency) {
        lastHistoryResponse.then()
                .body("transactions[0].availableBalanceAfter", org.hamcrest.Matchers.equalTo(expected))
                .body("transactions[0].currency", org.hamcrest.Matchers.equalTo(currency));
    }

    /**
     * Asserts the ledger's central invariant end to end: the reported balance is exactly the
     * sum of the movements a client can see.
     *
     * @param owner the account holder
     */
    @Then("^the available balance of \"([^\"]*)\" equals the sum of its transaction history$")
    public void theBalanceEqualsTheHistorySum(String owner) {
        iViewTheHistoryPaged(owner, 100, 0);

        BigDecimal sum = BigDecimal.ZERO;
        List<Map<String, Object>> transactions =
                lastHistoryResponse.jsonPath().getList("transactions");
        for (Map<String, Object> transaction : transactions) {
            BigDecimal amount = new BigDecimal(String.valueOf(transaction.get("amount")));
            sum = "WITHDRAWAL".equals(transaction.get("type")) ? sum.subtract(amount) : sum.add(amount);
        }

        BigDecimal reported = new BigDecimal(balance(owner).jsonPath().getString("availableBalance"));
        assertThat(reported).isEqualByComparingTo(sum);
    }

    // ---------------------------------------------------------------- helpers

    private Response balance(String owner) {
        Response response = api().when().get("/api/v1/accounts/{id}/balance", accountId(owner));
        response.then().statusCode(200);
        return response;
    }

    private Response move(String owner, String type, String amount, String currency, String reference) {
        return move(owner, type, amount, currency, reference, Instant.now());
    }

    private Response move(String owner, String type, String amount, String currency, String reference,
                          Instant occurredAt) {
        String body = reference == null
                ? """
                {"type":"%s","amount":"%s","currency":"%s","occurredAt":"%s"}
                """.formatted(type, amount, currency, occurredAt)
                : """
                {"type":"%s","amount":"%s","currency":"%s","reference":"%s","occurredAt":"%s"}
                """.formatted(type, amount, currency, reference, occurredAt);

        return api().body(body).when().post("/api/v1/accounts/{id}/transactions", accountId(owner));
    }
}
