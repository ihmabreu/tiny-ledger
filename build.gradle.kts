/*
 * Root build file for the Tiny Ledger multi-module project.
 *
 * Deliberately minimal: per Gradle best practice, cross-project configuration
 * (`subprojects { ... }` / `allprojects { ... }`) is avoided. Each module owns
 * its own build script, and shared versions live in `gradle/libs.versions.toml`.
 */
plugins {
    base
}

description = "Tiny Ledger - a small in-memory ledger service (Teya take-home assignment)"
