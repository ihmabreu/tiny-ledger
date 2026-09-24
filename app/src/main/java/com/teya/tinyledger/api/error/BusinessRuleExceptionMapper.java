package com.teya.tinyledger.api.error;

import com.teya.tinyledger.api.dto.ErrorResponse;
import com.teya.tinyledger.domain.exception.CurrencyMismatchException;
import com.teya.tinyledger.domain.exception.InsufficientFundsException;
import com.teya.tinyledger.domain.exception.LedgerException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps business rule violations to {@code 422 Unprocessable Entity}.
 *
 * <p>The distinction from {@code 400} is deliberate: a request that breaches the overdraft
 * allowance or targets the wrong currency is perfectly well formed &mdash; the server
 * understood it completely and still refused it. Clients can therefore treat {@code 400} as
 * "fix your request" and {@code 422} as "the ledger said no".</p>
 *
 * <p>Registered against the {@link LedgerException} root so that a newly added rule is mapped
 * correctly by default, rather than falling through to a {@code 500}.
 * {@link com.teya.tinyledger.domain.exception.AccountNotFoundException} is handled by a more
 * specific mapper and therefore keeps its {@code 404}.</p>
 *
 * @see InsufficientFundsException
 * @see CurrencyMismatchException
 */
@Provider
public class BusinessRuleExceptionMapper implements ExceptionMapper<LedgerException> {

    @Override
    public Response toResponse(LedgerException exception) {
        return Response.status(422)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of(exception.code(), exception.getMessage()))
                .build();
    }
}
