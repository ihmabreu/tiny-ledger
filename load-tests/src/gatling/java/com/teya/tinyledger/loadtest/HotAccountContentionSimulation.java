package com.teya.tinyledger.loadtest;

import io.gatling.javaapi.core.Simulation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Contention simulation: every virtual user hammers the <em>same</em> account.
 *
 * <p>Movements on one account are serialised by that account's lock, by design &mdash; it is
 * the only way to guarantee the overdraft allowance is never breached. This simulation exists
 * to put a number on what that costs.</p>
 *
 * <p>Reporting only the multi-account throughput figure would be flattering and misleading:
 * it measures the happy case where nothing contends. A reviewer deserves to see the worst case
 * too, and to see that it stays correct.</p>
 *
 * <p>Note the shape of the profile: writes to the hot account are deliberately mixed with
 * reads, because reads are lock-free (they observe an immutable snapshot) and should stay fast
 * even while writers queue.</p>
 *
 * @see MultiAccountThroughputSimulation for the uncontended counterpart
 */
public class HotAccountContentionSimulation extends Simulation {

    private static final AtomicReference<String> HOT_ACCOUNT_ID = new AtomicReference<>();

    /**
     * Defines the injection profile and the pass/fail thresholds.
     */
    public HotAccountContentionSimulation() {
        setUp(scenario("Hot account contention")
                .feed(Stream.generate(() -> Map.<String, Object>of("accountId", HOT_ACCOUNT_ID.get()))
                        .iterator())
                .repeat(20).on(
                        exec(LedgerLoadSupport.deposit())
                                .exec(LedgerLoadSupport.readBalance())
                                .exec(LedgerLoadSupport.withdraw())
                                .exec(LedgerLoadSupport.readHistory()))
                .injectOpen(constantUsersPerSec(40).during(Duration.ofSeconds(60))))
                .protocols(LedgerLoadSupport.protocol())
                .assertions(
                        // Correctness must not degrade under contention: a queued request is
                        // acceptable, a dropped or wrongly refused one is not.
                        global().failedRequests().percent().is(0.0),
                        // A more forgiving latency budget than the uncontended run, precisely
                        // because serialisation is the intended behaviour here.
                        global().responseTime().percentile3().lt(1000));
    }

    /**
     * Creates the single account every virtual user will share.
     *
     * <p>Done with a plain HTTP call rather than inside the scenario so that account creation
     * does not itself appear in the measurements.</p>
     */
    @Override
    public void before() {
        String baseUrl = System.getProperty(LedgerLoadSupport.BASE_URL_PROPERTY, "http://localhost:8080");

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/accounts"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"ownerName":"Hot Account","currency":"EUR","overdraftLimit":"100000000.00"}
                                    """))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 201) {
                throw new IllegalStateException(
                        "Could not create the shared account; is the ledger running at " + baseUrl
                                + "? Response was " + response.statusCode() + ": " + response.body());
            }

            HOT_ACCOUNT_ID.set(extractId(response.body()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach the ledger at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the shared account", e);
        }
    }

    private static String extractId(String responseBody) {
        int keyIndex = responseBody.indexOf("\"id\"");
        int start = responseBody.indexOf('"', responseBody.indexOf(':', keyIndex)) + 1;
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }
}
