package com.teya.tinyledger.api.error;

import com.teya.tinyledger.api.dto.ErrorResponse;
import com.teya.tinyledger.domain.exception.DuplicateIdempotencyKeyException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps an idempotency key reused for a different movement to {@code 409 Conflict}.
 *
 * <p>This is a distinct meaning from the generic {@code 422} produced by
 * {@link BusinessRuleExceptionMapper} for other {@code LedgerException}s: the request is not
 * being refused because it breaks a business rule, but because it conflicts with a prior
 * request identified by the same key. JAX-RS resolves the most specific exception mapper for a
 * thrown exception, so this mapper takes priority over the generic one for this exception type,
 * exactly as {@link AccountNotFoundExceptionMapper} already does for its own exception.</p>
 */
@Provider
public class IdempotencyKeyConflictExceptionMapper implements ExceptionMapper<DuplicateIdempotencyKeyException> {

    @Override
    public Response toResponse(DuplicateIdempotencyKeyException exception) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of(exception.code(), exception.getMessage()))
                .build();
    }
}
