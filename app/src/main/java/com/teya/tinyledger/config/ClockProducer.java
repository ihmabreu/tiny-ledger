package com.teya.tinyledger.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;

/**
 * Makes the system clock injectable.
 *
 * <p>Nothing in the ledger calls {@code Instant.now()} directly. Time arrives as a dependency,
 * which is what allows tests to pin it and assert on exact timestamps and ordering instead of
 * tolerating whatever the wall clock happened to say.</p>
 */
@ApplicationScoped
public class ClockProducer {

    /**
     * Produces the clock used to timestamp ledger activity.
     *
     * @return a UTC clock, so timestamps never depend on the server's locale or time zone
     */
    @Produces
    @ApplicationScoped
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
