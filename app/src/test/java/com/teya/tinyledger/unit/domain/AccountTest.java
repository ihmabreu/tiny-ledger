package com.teya.tinyledger.unit.domain;

import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.AccountBalance;
import com.teya.tinyledger.domain.BalanceSnapshot;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionPage;
import com.teya.tinyledger.domain.TransactionType;
import com.teya.tinyledger.domain.exception.CurrencyMismatchException;
import com.teya.tinyledger.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for the {@link Account} aggregate.
 *
 * <p>These exercise the rules the account itself is responsible for: the overdraft boundary,
 * currency consistency, history ordering and paging, and the promise that the incrementally
 * maintained balance always equals the sum of the visible history.</p>
 */
@Tag("unit")
@DisplayName("Account")
class AccountTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Instant OPENED_AT = Instant.parse("2026-01-01T09:00:00Z");

    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(OPENED_AT, ZoneOffset.UTC);
    }

    private Account accountWithOverdraft(String limit) {
        return Account.open("Ada Lovelace", EUR, Money.of(limit, EUR), clock);
    }

    @Nested
    @DisplayName("when opened")
    class WhenOpened {

        @Test
        @DisplayName("starts with a zero balance and no transactions")
        void open_withZeroLimit_startsEmptyWithZeroBalances() {
            AccountBalance balance = accountWithOverdraft("0").balance();

            assertThat(balance.availableBalance()).isEqualTo(Money.zero(EUR));
            assertThat(balance.accountBalance()).isEqualTo(Money.zero(EUR));
            assertThat(balance.transactionCount()).isZero();
            assertThat(balance.calculatedAt()).isEqualTo(OPENED_AT);
        }

        @Test
        @DisplayName("does not record the overdraft allowance as a transaction")
        void open_withOverdraftAllowance_doesNotRecordAllowanceAsTransaction() {
            Account account = accountWithOverdraft("500.00");

            assertThat(account.history(10, 0).transactions()).isEmpty();
            assertThat(account.balance().availableBalance()).isEqualTo(Money.zero(EUR));
            assertThat(account.balance().accountBalance()).isEqualTo(Money.of("500.00", EUR));
        }

        @ParameterizedTest(name = "owner name \"{0}\" is rejected")
        @ValueSource(strings = {"", "   "})
        @DisplayName("rejects a blank owner name")
        void open_blankOwnerName_throwsIllegalArgumentException(String ownerName) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Account.open(ownerName, EUR, Money.zero(EUR), clock));
        }

        @Test
        @DisplayName("rejects a negative overdraft allowance")
        void open_negativeOverdraftLimit_throwsIllegalArgumentException() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Account.open("Ada", EUR, Money.of("-1.00", EUR), clock));
        }

        @Test
        @DisplayName("rejects an overdraft allowance in another currency")
        void open_foreignCurrencyOverdraftLimit_throwsCurrencyMismatchException() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Account.open("Ada", EUR, Money.of("100.00", GBP), clock));
        }
    }

    @Nested
    @DisplayName("when recording movements")
    class WhenRecordingMovements {

        @Test
        @DisplayName("a deposit increases the available balance")
        void recordMovement_deposit_increasesAvailableBalance() {
            Account account = accountWithOverdraft("0");

            Transaction transaction =
                    account.recordMovement(TransactionType.DEPOSIT, Money.of("100.00", EUR), "Salary");

            assertThat(transaction.type()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(transaction.availableBalanceAfter()).isEqualTo(Money.of("100.00", EUR));
            assertThat(transaction.reference()).isEqualTo("Salary");
            assertThat(account.balance().availableBalance()).isEqualTo(Money.of("100.00", EUR));
        }

        @Test
        @DisplayName("a withdrawal decreases the available balance")
        void recordMovement_withdrawal_decreasesAvailableBalance() {
            Account account = accountWithOverdraft("0");
            account.recordMovement(TransactionType.DEPOSIT, Money.of("100.00", EUR), null);

            account.recordMovement(TransactionType.WITHDRAWAL, Money.of("40.00", EUR), null);

            assertThat(account.balance().availableBalance()).isEqualTo(Money.of("60.00", EUR));
        }

        @Test
        @DisplayName("numbers movements consecutively from one")
        void recordMovement_multipleMovements_numbersSequentiallyFromOne() {
            Account account = accountWithOverdraft("0");

            Transaction first = account.recordMovement(TransactionType.DEPOSIT, Money.of("1.00", EUR), null);
            Transaction second = account.recordMovement(TransactionType.DEPOSIT, Money.of("1.00", EUR), null);

            assertThat(first.sequence()).isEqualTo(1L);
            assertThat(second.sequence()).isEqualTo(2L);
        }

        @Test
        @DisplayName("normalises a blank reference to absent")
        void recordMovement_blankReference_normalisesToNull() {
            Account account = accountWithOverdraft("0");

            Transaction transaction =
                    account.recordMovement(TransactionType.DEPOSIT, Money.of("1.00", EUR), "   ");

            assertThat(transaction.reference()).isNull();
        }

        @Test
        @DisplayName("trims a surrounding-whitespace reference")
        void recordMovement_surroundingWhitespaceReference_trimsWhitespace() {
            Account account = accountWithOverdraft("0");

            Transaction transaction =
                    account.recordMovement(TransactionType.DEPOSIT, Money.of("1.00", EUR), "  Rent  ");

            assertThat(transaction.reference()).isEqualTo("Rent");
        }

        @ParameterizedTest(name = "an amount of {0} is rejected")
        @ValueSource(strings = {"0.00", "-5.00"})
        @DisplayName("rejects an amount that is not strictly positive")
        void recordMovement_nonPositiveAmount_throwsIllegalArgumentException(String amount) {
            Account account = accountWithOverdraft("0");

            assertThatIllegalArgumentException().isThrownBy(
                    () -> account.recordMovement(TransactionType.DEPOSIT, Money.of(amount, EUR), null));
        }

        @Test
        @DisplayName("rejects an amount in another currency")
        void recordMovement_foreignCurrencyAmount_throwsCurrencyMismatchException() {
            Account account = accountWithOverdraft("0");

            assertThatExceptionOfType(CurrencyMismatchException.class).isThrownBy(
                    () -> account.recordMovement(TransactionType.DEPOSIT, Money.of("10.00", GBP), null));
        }
    }

    @Nested
    @DisplayName("at the overdraft boundary")
    class OverdraftBoundary {

        @Test
        @DisplayName("refuses a withdrawal that would leave the balance below the allowance")
        void recordMovement_withdrawalExceedingOverdraft_throwsInsufficientFundsException() {
            Account account = accountWithOverdraft("100.00");

            assertThatExceptionOfType(InsufficientFundsException.class).isThrownBy(
                            () -> account.recordMovement(
                                    TransactionType.WITHDRAWAL, Money.of("100.01", EUR), null))
                    .satisfies(e -> {
                        assertThat(e.requestedAmount()).isEqualTo(Money.of("100.01", EUR));
                        assertThat(e.availableBalance()).isEqualTo(Money.zero(EUR));
                        assertThat(e.overdraftLimit()).isEqualTo(Money.of("100.00", EUR));
                    });
        }

        @Test
        @DisplayName("allows a withdrawal that lands exactly on the allowance")
        void recordMovement_withdrawalLandingExactlyOnOverdraft_allowsAndSetsAccountBalanceToZero() {
            Account account = accountWithOverdraft("100.00");

            account.recordMovement(TransactionType.WITHDRAWAL, Money.of("100.00", EUR), null);

            assertThat(account.balance().availableBalance()).isEqualTo(Money.of("-100.00", EUR));
            assertThat(account.balance().accountBalance()).isEqualTo(Money.zero(EUR));
        }

        @Test
        @DisplayName("refuses any withdrawal on an empty account without an allowance")
        void recordMovement_withdrawalOnZeroOverdraftAccount_throwsInsufficientFundsException() {
            Account account = accountWithOverdraft("0");

            assertThatExceptionOfType(InsufficientFundsException.class).isThrownBy(
                    () -> account.recordMovement(TransactionType.WITHDRAWAL, Money.of("0.01", EUR), null));
        }

        @Test
        @DisplayName("leaves the balance and history untouched when a movement is refused")
        void recordMovement_refusedWithdrawal_leavesBalanceAndHistoryUntouched() {
            Account account = accountWithOverdraft("0");
            account.recordMovement(TransactionType.DEPOSIT, Money.of("10.00", EUR), null);

            assertThatExceptionOfType(InsufficientFundsException.class).isThrownBy(
                    () -> account.recordMovement(TransactionType.WITHDRAWAL, Money.of("99.00", EUR), null));

            assertThat(account.balance().availableBalance()).isEqualTo(Money.of("10.00", EUR));
            assertThat(account.history(10, 0).total()).isEqualTo(1);
        }

        @Test
        @DisplayName("never refuses a deposit, however deep the account is overdrawn")
        void recordMovement_depositWhenOverdrawn_allowsAndIncreasesAvailableBalance() {
            Account account = accountWithOverdraft("100.00");
            account.recordMovement(TransactionType.WITHDRAWAL, Money.of("100.00", EUR), null);

            account.recordMovement(TransactionType.DEPOSIT, Money.of("0.01", EUR), null);

            assertThat(account.balance().availableBalance()).isEqualTo(Money.of("-99.99", EUR));
        }
    }

    @Nested
    @DisplayName("when reading history")
    class WhenReadingHistory {

        private Account account;

        @BeforeEach
        void recordFiveMovements() {
            account = accountWithOverdraft("0");
            for (int i = 1; i <= 5; i++) {
                account.recordMovement(TransactionType.DEPOSIT, Money.of(i + ".00", EUR), "deposit-" + i);
            }
        }

        @Test
        @DisplayName("returns the most recent movement first")
        void history_multipleMovements_returnsMostRecentFirst() {
            TransactionPage page = account.history(10, 0);

            assertThat(page.transactions())
                    .extracting(Transaction::reference)
                    .containsExactly("deposit-5", "deposit-4", "deposit-3", "deposit-2", "deposit-1");
        }

        @Test
        @DisplayName("limits the page to the requested size")
        void history_withLimit_returnsRequestedPageSizeAndTotalCount() {
            TransactionPage page = account.history(2, 0);

            assertThat(page.transactions()).hasSize(2);
            assertThat(page.total()).isEqualTo(5);
            assertThat(page.limit()).isEqualTo(2);
        }

        @Test
        @DisplayName("skips the requested number of most recent movements")
        void history_withOffset_skipsRequestedNumberOfMovements() {
            TransactionPage page = account.history(2, 2);

            assertThat(page.transactions())
                    .extracting(Transaction::reference)
                    .containsExactly("deposit-3", "deposit-2");
            assertThat(page.offset()).isEqualTo(2);
        }

        @Test
        @DisplayName("returns an empty page when the offset is past the end")
        void history_offsetPastEnd_returnsEmptyTransactionsList() {
            TransactionPage page = account.history(10, 99);

            assertThat(page.transactions()).isEmpty();
            assertThat(page.total()).isEqualTo(5);
        }

        @Test
        @DisplayName("rejects a non-positive limit and a negative offset")
        void history_invalidLimitOrOffset_throwsIllegalArgumentException() {
            assertThatIllegalArgumentException().isThrownBy(() -> account.history(0, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> account.history(10, -1));
        }

        @Test
        @DisplayName("hands out an immutable page")
        void history_returnedTransactionsList_throwsUnsupportedOperationExceptionOnMutation() {
            List<Transaction> transactions = account.history(10, 0).transactions();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> transactions.remove(0));
        }
    }

    @Nested
    @DisplayName("balance snapshot")
    class Snapshot {

        @Test
        @DisplayName("always equals the sum of the recorded history")
        void balanceSnapshot_afterMovements_equalsSumOfRecordedHistory() {
            Account account = accountWithOverdraft("50.00");
            account.recordMovement(TransactionType.DEPOSIT, Money.of("100.00", EUR), null);
            account.recordMovement(TransactionType.WITHDRAWAL, Money.of("30.00", EUR), null);
            account.recordMovement(TransactionType.WITHDRAWAL, Money.of("90.00", EUR), null);

            Account.ConsistentView view = account.consistentView();
            Money replayed = view.transactions().stream()
                    .map(Transaction::signedAmount)
                    .reduce(Money.zero(EUR), Money::add);

            assertThat(view.balanceSnapshot().availableBalance()).isEqualTo(replayed);
            assertThat(replayed).isEqualTo(Money.of("-20.00", EUR));
        }

        @Test
        @DisplayName("can be rebuilt by replaying only the movements after its marker")
        void balanceSnapshot_foldAllAfterMarker_matchesFullSnapshot() {
            Account account = accountWithOverdraft("0");
            account.recordMovement(TransactionType.DEPOSIT, Money.of("100.00", EUR), null);
            BalanceSnapshot marker = account.balanceSnapshot();

            account.recordMovement(TransactionType.DEPOSIT, Money.of("5.00", EUR), null);
            account.recordMovement(TransactionType.WITHDRAWAL, Money.of("25.00", EUR), null);

            Account.ConsistentView view = account.consistentView();
            List<Transaction> afterMarker = view.transactions().stream()
                    .filter(t -> t.sequence() > marker.lastAppliedSequence())
                    .toList();

            assertThat(marker.foldAll(afterMarker)).isEqualTo(view.balanceSnapshot());
        }

        @Test
        @DisplayName("tracks the timestamp of the newest movement")
        void balanceSnapshot_afterDeposit_tracksTimestampOfNewestMovement() {
            Instant later = OPENED_AT.plus(Duration.ofMinutes(5));
            Account account = Account.open("Ada", EUR, Money.zero(EUR),
                    Clock.fixed(later, ZoneOffset.UTC));

            account.recordMovement(TransactionType.DEPOSIT, Money.of("1.00", EUR), null);

            assertThat(account.balanceSnapshot().lastAppliedAt()).isEqualTo(later);
            assertThat(account.balanceSnapshot().hasTransactions()).isTrue();
        }
    }
}
