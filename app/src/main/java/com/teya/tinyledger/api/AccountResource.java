package com.teya.tinyledger.api;

import com.teya.tinyledger.api.dto.AccountResponse;
import com.teya.tinyledger.api.dto.BalanceResponse;
import com.teya.tinyledger.api.dto.OpenAccountRequest;
import com.teya.tinyledger.api.mapper.LedgerRequestMapper;
import com.teya.tinyledger.api.mapper.LedgerResponseMapper;
import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.Money;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * HTTP endpoints for the account lifecycle and balance enquiries.
 *
 * <p>Implements the {@code /api/v1/accounts} operations of the hand-written OpenAPI contract in
 * {@code src/main/resources/META-INF/openapi.yaml}. The contract is the source of truth; the
 * integration tests assert that these responses continue to satisfy it.</p>
 *
 * <p>The resource is annotated {@link RunOnVirtualThread}, so each request is served on its own
 * virtual thread. That keeps the code plainly imperative &mdash; call, return, throw &mdash;
 * while still allowing a very large number of requests to be in flight, without adopting the
 * reactive programming model.</p>
 */
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AccountResource {

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
    public AccountResource(LedgerService ledgerService,
                           LedgerRequestMapper requestMapper,
                           LedgerResponseMapper responseMapper) {
        this.ledgerService = ledgerService;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
    }

    /**
     * Opens a new account.
     *
     * @param request the account to open
     * @param uriInfo used to build the {@code Location} header
     * @return {@code 201 Created} with the new account
     */
    @POST
    public Response openAccount(@Valid OpenAccountRequest request, @jakarta.ws.rs.core.Context UriInfo uriInfo) {
        Currency currency = requestMapper.toCurrency(request);
        Money overdraftLimit = requestMapper.toOverdraftLimit(request, currency);

        Account account = ledgerService.openAccount(request.ownerName(), currency, overdraftLimit);

        return Response
                .created(uriInfo.getAbsolutePathBuilder().path(account.id().toString()).build())
                .entity(responseMapper.toAccountResponse(account))
                .build();
    }

    /**
     * Lists every account.
     *
     * @return all accounts, oldest first
     */
    @GET
    public List<AccountResponse> listAccounts() {
        return responseMapper.toAccountResponses(ledgerService.listAccounts());
    }

    /**
     * Returns a single account.
     *
     * @param accountId the account to return
     * @return the account
     */
    @GET
    @Path("/{accountId}")
    public AccountResponse getAccount(@PathParam("accountId") UUID accountId) {
        return responseMapper.toAccountResponse(ledgerService.getAccount(accountId));
    }

    /**
     * Returns an account's current balance.
     *
     * @param accountId the account to inspect
     * @return the available balance, the account balance and the overdraft allowance
     */
    @GET
    @Path("/{accountId}/balance")
    public BalanceResponse getBalance(@PathParam("accountId") UUID accountId) {
        return responseMapper.toBalanceResponse(ledgerService.getBalance(accountId));
    }
}
