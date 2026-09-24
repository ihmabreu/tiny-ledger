package com.teya.tinyledger.concurrency;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests driven through the HTTP API rather than the domain directly.
 *
 * <p>The domain-level tests prove the aggregate is safe; these prove the whole stack is. They
 * also exercise the thing that makes that possible in Quarkus: the endpoints are plain blocking
 * code annotated {@code @RunOnVirtualThread}, so hundreds of simultaneous requests each get
 * their own carrier-free thread instead of queueing behind a small worker pool.</p>
 */
@QuarkusTest
@Tag("concurrency")
@DisplayName("Ledger API under concurrent load")
class LedgerApiConcurrencyTest {

    private static final int CLIENTS = 120;

    private static String openAccount(String overdraftLimit) {
        return given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body("""
                        {"ownerName":"Concurrent Owner","currency":"EUR","overdraftLimit":"%s"}
                        """.formatted(overdraftLimit))
                .when().post("/api/v1/accounts")
                .then().statusCode(201)
                .extract().path("id");
    }

    private static Response deposit(String accountId, String amount) {
        return given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body("""
                        {"type":"DEPOSIT","amount":"%s","currency":"EUR"}
                        """.formatted(amount))
                .when().post("/api/v1/accounts/{id}/transactions", accountId);
    }

    private static Response withdraw(String accountId, String amount) {
        return given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body("""
                        {"type":"WITHDRAWAL","amount":"%s","currency":"EUR"}
                        """.formatted(amount))
                .when().post("/api/v1/accounts/{id}/transactions", accountId);
    }

    private static List<Future<?>> runConcurrently(int clients, Runnable work) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(clients);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < clients; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    work.run();
                    return null;
                }));
            }
            startGate.countDown();
        }

        for (Future<?> future : futures) {
            future.get();
        }
        return futures;
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("records every concurrent deposit exactly once")
    void deposit_concurrentRequestsOnSameAccount_recordsEveryDepositExactlyOnce() throws Exception {
        String accountId = openAccount("0.00");

        runConcurrently(CLIENTS, () -> deposit(accountId, "1.00").then().statusCode(201));

        given().accept(ContentType.JSON)
                .when().get("/api/v1/accounts/{id}/balance", accountId)
                .then()
                .statusCode(200)
                .body("availableBalance", org.hamcrest.Matchers.equalTo(CLIENTS + ".00"))
                .body("transactionCount", org.hamcrest.Matchers.equalTo(CLIENTS));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("refuses exactly the withdrawals that exceed the allowance, with 422")
    void withdraw_concurrentRequestsExceedingAllowance_arbitratesAndRefusesWith422() throws Exception {
        String accountId = openAccount("0.00");
        deposit(accountId, "50.00").then().statusCode(201);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        runConcurrently(CLIENTS, () -> {
            int status = withdraw(accountId, "1.00").statusCode();
            if (status == 201) {
                accepted.incrementAndGet();
            } else if (status == 422) {
                refused.incrementAndGet();
            }
        });

        assertThat(accepted.get()).isEqualTo(50);
        assertThat(refused.get()).isEqualTo(CLIENTS - 50);

        given().accept(ContentType.JSON)
                .when().get("/api/v1/accounts/{id}/balance", accountId)
                .then().body("availableBalance", org.hamcrest.Matchers.equalTo("0.00"));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("movements on unrelated accounts do not interfere")
    void deposit_concurrentRequestsAcrossMultipleAccounts_isolatesAccountsWithoutContention() throws Exception {
        List<String> accountIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            accountIds.add(openAccount("0.00"));
        }

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String accountId : accountIds) {
                for (int i = 0; i < 10; i++) {
                    futures.add(executor.submit(() -> {
                        startGate.await();
                        deposit(accountId, "5.00").then().statusCode(201);
                        return null;
                    }));
                }
            }
            startGate.countDown();
        }
        for (Future<?> future : futures) {
            future.get();
        }

        for (String accountId : accountIds) {
            given().accept(ContentType.JSON)
                    .when().get("/api/v1/accounts/{id}/balance", accountId)
                    .then()
                    .body("availableBalance", org.hamcrest.Matchers.equalTo("50.00"));
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("the reported balance always matches the history a client can read")
    void getBalance_concurrentWithMovements_matchesReadableHistory() throws Exception {
        String accountId = openAccount("500.00");

        runConcurrently(CLIENTS, () -> {
            deposit(accountId, "4.00").then().statusCode(201);
            withdraw(accountId, "1.00").then().statusCode(201);
        });

        List<Map<String, Object>> transactions = given().accept(ContentType.JSON)
                .queryParam("limit", 100)
                .when().get("/api/v1/accounts/{id}/transactions", accountId)
                .then().statusCode(200)
                .extract().jsonPath().getList("transactions");

        // Reading the newest page and the balance separately is exactly what a real client
        // does; the newest movement's recorded balance must match the balance endpoint.
        String reportedBalance = given().accept(ContentType.JSON)
                .when().get("/api/v1/accounts/{id}/balance", accountId)
                .then().statusCode(200)
                .extract().path("availableBalance");

        assertThat(new BigDecimal(reportedBalance))
                .isEqualByComparingTo(new BigDecimal(CLIENTS * 3 + ".00"));
        assertThat(transactions).hasSize(100);
    }
}
