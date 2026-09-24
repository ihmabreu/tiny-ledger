package com.teya.tinyledger.api.error;

import com.teya.tinyledger.api.dto.ErrorResponse;
import com.teya.tinyledger.domain.exception.AccountNotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps an unknown account to {@code 404 Not Found}.
 */
@Provider
public class AccountNotFoundExceptionMapper implements ExceptionMapper<AccountNotFoundException> {

    @Override
    public Response toResponse(AccountNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of(exception.code(), exception.getMessage()))
                .build();
    }
}
