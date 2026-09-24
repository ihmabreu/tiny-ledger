package com.teya.tinyledger.api.error;

import com.teya.tinyledger.api.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps malformed input rejected by the domain to {@code 400 Bad Request}.
 *
 * <p>Domain value objects such as {@link com.teya.tinyledger.domain.Money} guard their own
 * invariants with {@link IllegalArgumentException}. Those guards are the last line of defence
 * behind Bean Validation, and they describe caller mistakes, so they belong in the same
 * {@code 400} bucket.</p>
 */
@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {

    /** Error code reported for input the server could not accept. */
    public static final String CODE = "VALIDATION_FAILED";

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of(CODE, exception.getMessage()))
                .build();
    }
}
