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
import jakarta.ws.rs.HeaderParam;
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

    /**
     * Name of the mandatory header carrying the caller-supplied idempotency key.
     *
     * <p>A compile-time constant, which is what allows it to be used as the
     * {@link HeaderParam} value, so the binding and the validation message below cannot drift
     * apart.</p>
     *
     * <p><strong>This constant is not the source of truth for the header's name</strong> &mdash;
     * {@code META-INF/openapi.yaml} is, and this is one expression of it. The test tiers are a
     * second, deliberately independent expression: they spell the header out as a literal, and
     * they are validated against the contract on the way out, so a request naming the header
     * anything else is refused before it is even sent. Pointing either expression at the other
     * would collapse a cross-check into a tautology &mdash; renaming this field would then
     * rename the header the service accepts with nothing left to notice.</p>
     */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

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
     * <p>The {@code Idempotency-Key} header is mandatory: it is the only reliable way to tell
     * a network-level retry of this exact request apart from a second, coincidentally
     * identical, movement. Replaying the same key with the same {@code type} and {@code amount}
     * returns the original {@code 201} response again rather than recording the movement a
     * second time; replaying it with a different type or amount is refused with
     * {@code 409 Conflict}.</p>
     *
     * <p>The caller's {@code occurredAt} is carried through to the stored movement as reference
     * data. It never affects where the movement lands in the history, what the balance becomes,
     * or whether an overdraft check passes &mdash; those follow the ledger's own clock and the
     * account-scoped sequence. See {@link com.teya.tinyledger.domain.Transaction}.</p>
     *
     * @param accountId      the account to move money on
     * @param idempotencyKey a caller-supplied key identifying this movement, reused verbatim on
     *                       retry
     * @param request        the movement to record
     * @param uriInfo        used to build the {@code Location} header
     * @return {@code 201 Created} with the recorded transaction
     * @throws IllegalArgumentException if the header is missing or blank
     */
    @POST
    public Response recordTransaction(@PathParam("accountId") UUID accountId,
                                      @HeaderParam(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                      @Valid RecordTransactionRequest request,
                                      @Context UriInfo uriInfo) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Header '" + IDEMPOTENCY_KEY_HEADER + "' is required");
        }

        Money amount = requestMapper.toAmount(request);

        Transaction transaction = ledgerService.recordMovement(
                accountId, request.type(), amount, request.reference(), request.occurredAt(), idempotencyKey);

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
