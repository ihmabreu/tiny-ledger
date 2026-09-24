package com.teya.tinyledger.loadtest;

import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;

/**
 * Throughput simulation: many virtual users, each on its own account.
 *
 * <p>This is the simulation that measures how well the service actually parallelises. Because
 * every user owns a different account, no two requests contend for the same lock, so the only
 * limits are the HTTP stack and the machine &mdash; which is exactly what virtual threads are
 * meant to let us push.</p>
 *
 * <p>Run it with:</p>
 *
 * <pre>
 * ./gradlew :app:quarkusRun                     # terminal 1
 * ./gradlew :load-tests:gatlingRun              # terminal 2
 * </pre>
 *
 * @see HotAccountContentionSimulation for the deliberately serialised counterpart
 */
public class MultiAccountThroughputSimulation extends Simulation {

    /**
     * Defines the injection profile and the pass/fail thresholds.
     */
    public MultiAccountThroughputSimulation() {
        setUp(LedgerLoadSupport.mixedTrafficScenario(10).injectOpen(
                        rampUsersPerSec(1).to(60).during(Duration.ofSeconds(30)),
                        constantUsersPerSec(60).during(Duration.ofSeconds(60))))
                .protocols(LedgerLoadSupport.protocol())
                .assertions(
                        // An in-memory ledger has no excuse for failing a request under load.
                        global().failedRequests().percent().is(0.0),
                        global().responseTime().percentile3().lt(200),
                        global().responseTime().max().lt(2000),
                        // Reads are pure snapshot lookups and should stay firmly in single-digit
                        // milliseconds even while writes are in flight.
                        details("Read balance").responseTime().percentile3().lt(100));
    }
}
