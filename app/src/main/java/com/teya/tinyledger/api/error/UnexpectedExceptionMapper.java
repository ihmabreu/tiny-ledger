package com.teya.tinyledger.api.error;

import com.teya.tinyledger.api.dto.ErrorResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Last-resort mapper turning any unanticipated failure into a {@code 500 Internal Server Error}
 * that still matches the contract's error shape.
 *
 * <p>The exception message is logged rather than returned: a client can do nothing useful with
 * an internal stack trace, and leaking one is a needless disclosure.</p>
 *
 * <p>{@link WebApplicationException} is re-thrown untouched so that framework-level responses
 * such as {@code 404} for an unmatched route or {@code 415} for an unsupported media type keep
 * their intended status.</p>
 */
@Provider
public class UnexpectedExceptionMapper implements ExceptionMapper<Throwable> {

    /** Error code reported when the ledger failed for a reason it does not model. */
    public static final String CODE = "INTERNAL_ERROR";

    private static final Logger LOG = Logger.getLogger(UnexpectedExceptionMapper.class);

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof WebApplicationException webApplicationException) {
            Response response = webApplicationException.getResponse();
            return Response.fromResponse(response)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ErrorResponse.of(
                            statusCodeToErrorCode(response.getStatus()),
                            exception.getMessage() == null
                                    ? "The request could not be processed."
                                    : exception.getMessage()))
                    .build();
        }

        LOG.error("Unhandled failure while processing a request", exception);

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of(CODE, "The ledger could not process the request."))
                .build();
    }

    private static String statusCodeToErrorCode(int status) {
        return switch (status) {
            case 400 -> "VALIDATION_FAILED";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> CODE;
        };
    }
}
