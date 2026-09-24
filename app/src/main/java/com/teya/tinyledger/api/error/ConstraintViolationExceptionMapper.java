package com.teya.tinyledger.api.error;

import com.teya.tinyledger.api.dto.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Comparator;
import java.util.List;

/**
 * Maps Bean Validation failures to {@code 400 Bad Request}.
 *
 * <p>Every rejected field is reported at once, sorted for determinism, so a caller can fix a
 * payload in a single round trip instead of discovering one problem per attempt.</p>
 */
@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    /** Error code reported for payloads that fail declarative validation. */
    public static final String CODE = "VALIDATION_FAILED";

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        List<String> details = exception.getConstraintViolations().stream()
                .map(ConstraintViolationExceptionMapper::describe)
                .sorted(Comparator.naturalOrder())
                .toList();

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of(CODE, "The request payload is invalid.", details))
                .build();
    }

    private static String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        String field = lastDot >= 0 ? path.substring(lastDot + 1) : path;
        return "%s: %s".formatted(field, violation.getMessage());
    }
}
