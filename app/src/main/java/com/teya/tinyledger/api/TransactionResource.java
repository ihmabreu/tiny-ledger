package com.teya.tinyledger.api;

import com.teya.tinyledger.api.dto.RecordTransactionRequest;
import com.teya.tinyledger.api.dto.TransactionHistoryResponse;
import com.teya.tinyledger.api.mapper.LedgerRequestMapper;
import com.teya.tinyledger.api.mapper.LedgerResponseMapper;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.service.LedgerService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.UUID;

/**
 * HTTP endpoints for recording money movements and reading transaction history.
 *
 * <p>History is nested under its account because a transaction has no meaning outside one; the
 * URL therefore reads as the resource hierarchy it actually is.</p>
 *
 * <p>Like {@link AccountResource}, requests are served on virtual threads so that blocking,
 * easy-to-read code still scales.</p>
 */
@Path("/api/v1/accounts/{accountId}/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class TransactionResource {

    private final LedgerService ledgerService;
    private final LedgerRequestMapper requestMapper;
    private final LedgerResponseMapper responseMapper;

    /**
     * Creates the resource.
     *
     * @param ledgerService  the ledger use cases
     * @param requestMapper  translates payloads into domain values
     * @param responseMapper translates domain objects into representations
     */
    @Inject
    public TransactionResource(LedgerService ledgerService,
                               LedgerRequestMapper requestMapper,
                               LedgerResponseMapper responseMapper) {
        this.ledgerService = ledgerService;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
    }

    /**
     * Records a deposit or a withdrawal.
     *
     * @param accountId the account to move money on
     * @param request   the movement to record
     * @param uriInfo   used to build the {@code Location} header
     * @return {@code 201 Created} with the recorded transaction
     */
    @POST
    public Response recordTransaction(@PathParam("accountId") UUID accountId,
                                      @Valid RecordTransactionRequest request,
                                      @Context UriInfo uriInfo) {
        Money amount = requestMapper.toAmount(request);

        Transaction transaction =
                ledgerService.recordMovement(accountId, request.type(), amount, request.reference());

        return Response
                .created(uriInfo.getAbsolutePath())
                .entity(responseMapper.toTransactionResponse(transaction))
                .build();
    }

    /**
     * Reads a page of transaction history, most recent movement first.
     *
     * <p>The paging parameters are accepted as text and parsed here so that a non-numeric value
     * is reported as a {@code 400 Bad Request} describing the offending parameter, rather than
     * the {@code 404} that automatic parameter conversion would produce.</p>
     *
     * @param accountId the account to inspect
     * @param limit     the page size, defaulting to {@value LedgerService#DEFAULT_PAGE_SIZE}
     * @param offset    how many of the most recent transactions to skip, defaulting to none
     * @return the requested page
     */
    @GET
    public TransactionHistoryResponse getHistory(@PathParam("accountId") UUID accountId,
                                                 @QueryParam("limit") String limit,
                                                 @QueryParam("offset") String offset) {
        return responseMapper.toHistoryResponse(ledgerService.getHistory(
                accountId,
                parseOptionalInt(limit, "limit"),
                parseOptionalInt(offset, "offset")));
    }

    private static Integer parseOptionalInt(String raw, String parameterName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Query parameter '%s' must be an integer, but was '%s'".formatted(parameterName, raw));
        }
    }
}
