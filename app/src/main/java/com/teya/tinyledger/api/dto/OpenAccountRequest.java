package com.teya.tinyledger.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for opening an account.
 *
 * @param ownerName      name of the account holder
 * @param currency       ISO 4217 code of the currency the account is held in
 * @param overdraftLimit agreed overdraft allowance; defaults to zero when omitted
 */
public record OpenAccountRequest(
        @NotBlank(message = "ownerName must not be blank")
        @Size(max = 120, message = "ownerName must be at most 120 characters")
        String ownerName,

        @NotBlank(message = "currency must not be blank")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @PositiveOrZero(message = "overdraftLimit must not be negative")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal overdraftLimit) {
}
