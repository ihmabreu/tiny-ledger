package com.teya.tinyledger.unit.service;

import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionPage;
import com.teya.tinyledger.domain.TransactionType;
import com.teya.tinyledger.domain.exception.AccountNotFoundException;
import com.teya.tinyledger.domain.exception.CurrencyMismatchException;
import com.teya.tinyledger.domain.exception.InsufficientFundsException;
import com.teya.tinyledger.repository.AccountRepository;
import com.teya.tinyledger.repository.InMemoryAccountRepository;
import com.teya.tinyledger.service.DefaultLedgerService;
import com.teya.tinyledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link DefaultLedgerService}.
 *
 * <p>The service is tested against the real in-memory repository rather than a mock: the
 * repository is a trivial map and substituting a mock would only assert that the service calls
 * the methods this test already exercises through its observable behaviour.</p>
 */
@Tag("unit")
@DisplayName("Ledger service")
class DefaultLedgerServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency GBP = Currency.getInstance("GBP");

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        AccountRepository repository = new InMemoryAccountRepository();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        ledgerService = new DefaultLedgerService(repository, clock);
    }

    private Account openEurAccount(String overdraftLimit) {
        return ledgerService.openAccount("Ada Lovelace", EUR, Money.of(overdraftLimit, EUR));
    }

    @Nested
    @DisplayName("opening accounts")
    class OpeningAccounts {

        @Test
        @DisplayName("stores the account so it can be read back")
        void openAccount_validDetails_savesAndCanBeRetrieved() {
            Account opened = openEurAccount("0");

            assertThat(ledgerService.getAccount(opened.id())).isSameAs(opened);
            assertThat(ledgerService.listAccounts()).containsExactly(opened);
        }

        @Test
        @DisplayName("defaults the overdraft allowance to zero")
        void openAccount_nullOverdraftLimit_defaultsOverdraftToZero() {
            Account opened = ledgerService.openAccount("Ada", EUR, null);

            assertThat(opened.overdraftLimit()).isEqualTo(Money.zero(EUR));
        }

        @Test
        @DisplayName("lists accounts in the order they were opened")
        void listAccounts_multipleAccounts_returnsInOpeningOrder() {
            Account first = openEurAccount("0");
            Account second = openEurAccount("0");

            assertThat(ledgerService.listAccounts()).containsExactly(first, second);
        }

        @Test
        @DisplayName("reports an unknown account rather than returning nothing")
        void getAccount_unknownAccountId_throwsAccountNotFoundException() {
            UUID unknown = UUID.randomUUID();

            assertThatExceptionOfType(AccountNotFoundException.class)
                    .isThrownBy(() -> ledgerService.getAccount(unknown))
                    .satisfies(e -> assertThat(e.accountId()).isEqualTo(unknown));
        }
    }

    @Nested
    @DisplayName("recording money movements")
    class RecordingMovements {

        @Test
        @DisplayName("records a deposit and reflects it in the balance")
        void deposit_validAmountAndReference_recordsTransactionAndIncreasesBalance() {
            Account account = openEurAccount("0");

            Transaction transaction =
                    ledgerService.deposit(account.id(), Money.of("250.00", EUR), "Salary");

            assertThat(transaction.type()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(ledgerService.getBalance(account.id()).availableBalance())
                    .isEqualTo(Money.of("250.00", EUR));
        }

        @Test
        @DisplayName("records a withdrawal and reflects it in the balance")
        void withdraw_validAmountAndReference_recordsTransactionAndDecreasesBalance() {
            Account account = openEurAccount("0");
            ledgerService.deposit(account.id(), Money.of("250.00", EUR), null);

            ledgerService.withdraw(account.id(), Money.of("50.00", EUR), "ATM");

            assertThat(ledgerService.getBalance(account.id()).availableBalance())
                    .isEqualTo(Money.of("200.00", EUR));
        }

        @Test
        @DisplayName("refuses a withdrawal that would breach the overdraft allowance")
        void withdraw_amountExceedingOverdraft_throwsInsufficientFundsException() {
            Account account = openEurAccount("100.00");

            assertThatExceptionOfType(InsufficientFundsException.class).isThrownBy(
                    () -> ledgerService.withdraw(account.id(), Money.of("100.01", EUR), null));
        }

        @Test
        @DisplayName("refuses a movement in another currency")
        void deposit_mismatchedCurrency_throwsCurrencyMismatchException() {
            Account account = openEurAccount("0");

            assertThatExceptionOfType(CurrencyMismatchException.class).isThrownBy(
                    () -> ledgerService.deposit(account.id(), Money.of("10.00", GBP), null));
        }

        @Test
        @DisplayName("refuses a movement on an unknown account")
        void deposit_unknownAccountId_throwsAccountNotFoundException() {
            assertThatExceptionOfType(AccountNotFoundException.class).isThrownBy(
                    () -> ledgerService.deposit(UUID.randomUUID(), Money.of("10.00", EUR), null));
        }

        @Test
        @DisplayName("keeps the available balance equal to the sum of the history")
        void getBalance_afterMultipleMovements_equalsSumOfRecordedHistory() {
            Account account = openEurAccount("500.00");
            ledgerService.deposit(account.id(), Money.of("100.00", EUR), null);
            ledgerService.withdraw(account.id(), Money.of("300.00", EUR), null);
            ledgerService.deposit(account.id(), Money.of("0.55", EUR), null);

            Money replayed = ledgerService.getHistory(account.id(), 100, 0).transactions().stream()
                    .map(Transaction::signedAmount)
                    .reduce(Money.zero(EUR), Money::add);

            assertThat(ledgerService.getBalance(account.id()).availableBalance()).isEqualTo(replayed);
        }

        @Test
        @DisplayName("reports the account balance as the available balance plus the allowance")
        void getBalance_withOverdraftAllowance_reportsBothAvailableAndAccountBalance() {
            Account account = openEurAccount("200.00");
            ledgerService.deposit(account.id(), Money.of("50.00", EUR), null);

            var balance = ledgerService.getBalance(account.id());

            assertThat(balance.availableBalance()).isEqualTo(Money.of("50.00", EUR));
            assertThat(balance.accountBalance()).isEqualTo(Money.of("250.00", EUR));
            assertThat(balance.overdraftLimit()).isEqualTo(Money.of("200.00", EUR));
        }
    }

    @Nested
    @DisplayName("reading history")
    class ReadingHistory {

        @Test
        @DisplayName("applies the default page size when none is requested")
        void getHistory_nullLimitAndOffset_appliesDefaultPageSize() {
            Account account = openEurAccount("0");
            for (int i = 0; i < LedgerService.DEFAULT_PAGE_SIZE + 5; i++) {
                ledgerService.deposit(account.id(), Money.of("1.00", EUR), null);
            }

            TransactionPage page = ledgerService.getHistory(account.id(), null, null);

            assertThat(page.transactions()).hasSize(LedgerService.DEFAULT_PAGE_SIZE);
            assertThat(page.total()).isEqualTo(LedgerService.DEFAULT_PAGE_SIZE + 5);
            assertThat(page.offset()).isZero();
        }

        @ParameterizedTest(name = "a limit of {0} is rejected")
        @ValueSource(ints = {0, -1, LedgerService.MAX_PAGE_SIZE + 1})
        @DisplayName("rejects a page size outside the supported range")
        void getHistory_limitOutsideSupportedRange_throwsIllegalArgumentException(int limit) {
            Account account = openEurAccount("0");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ledgerService.getHistory(account.id(), limit, 0));
        }

        @Test
        @DisplayName("accepts the largest supported page size")
        void getHistory_maximumSupportedLimit_returnsRequestedLimit() {
            Account account = openEurAccount("0");

            TransactionPage page =
                    ledgerService.getHistory(account.id(), LedgerService.MAX_PAGE_SIZE, 0);

            assertThat(page.limit()).isEqualTo(LedgerService.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("rejects a negative offset")
        void getHistory_negativeOffset_throwsIllegalArgumentException() {
            Account account = openEurAccount("0");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ledgerService.getHistory(account.id(), 10, -1));
        }

        @Test
        @DisplayName("refuses to read the history of an unknown account")
        void getHistory_unknownAccountId_throwsAccountNotFoundException() {
            assertThatExceptionOfType(AccountNotFoundException.class)
                    .isThrownBy(() -> ledgerService.getHistory(UUID.randomUUID(), 10, 0));
        }

        @Test
        @DisplayName("validates paging before resolving the account")
        void getHistory_invalidPagingWithUnknownAccount_validatesPagingBeforeAccountLookup() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ledgerService.getHistory(UUID.randomUUID(), 0, 0));
        }
    }
}
