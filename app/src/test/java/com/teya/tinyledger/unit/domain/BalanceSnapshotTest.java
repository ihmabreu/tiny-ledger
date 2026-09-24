package com.teya.tinyledger.unit.domain;

import com.teya.tinyledger.domain.BalanceSnapshot;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link BalanceSnapshot}, the incremental balance cache.
 *
 * <p>The snapshot is what makes a balance read O(1) instead of a full replay of history, so its
 * folding rules &mdash; and its refusal to fold anything out of order &mdash; are what keep that
 * optimisation honest.</p>
 */
@Tag("unit")
@DisplayName("Balance snapshot")
class BalanceSnapshotTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Instant OPENED_AT = Instant.parse("2026-01-01T09:00:00Z");

    private static Transaction transaction(long sequence, TransactionType type, String amount, String balanceAfter) {
        return new Transaction(
                UUID.randomUUID(),
                ACCOUNT_ID,
                sequence,
                type,
                Money.of(amount, EUR),
                Money.of(balanceAfter, EUR),
                null,
                OPENED_AT.plusSeconds(sequence));
    }

    @Test
    @DisplayName("opens at zero with nothing applied")
    void opening_initialState_startsAtZeroWithNoAppliedTransactions() {
        BalanceSnapshot snapshot = BalanceSnapshot.opening(EUR, OPENED_AT);

        assertThat(snapshot.availableBalance()).isEqualTo(Money.zero(EUR));
        assertThat(snapshot.lastAppliedSequence()).isZero();
        assertThat(snapshot.lastAppliedTransactionId()).isNull();
        assertThat(snapshot.lastAppliedAt()).isEqualTo(OPENED_AT);
        assertThat(snapshot.hasTransactions()).isFalse();
        assertThat(snapshot.transactionCount()).isZero();
    }

    @Test
    @DisplayName("folding a movement advances the balance, the marker and the timestamp")
    void fold_singleDeposit_advancesBalanceMarkerAndTimestamp() {
        Transaction deposit = transaction(1, TransactionType.DEPOSIT, "100.00", "100.00");

        BalanceSnapshot folded = BalanceSnapshot.opening(EUR, OPENED_AT).fold(deposit);

        assertThat(folded.availableBalance()).isEqualTo(Money.of("100.00", EUR));
        assertThat(folded.lastAppliedSequence()).isEqualTo(1L);
        assertThat(folded.lastAppliedTransactionId()).isEqualTo(deposit.id());
        assertThat(folded.lastAppliedAt()).isEqualTo(deposit.recordedAt());
        assertThat(folded.transactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("folding a withdrawal subtracts it")
    void fold_depositFollowedByWithdrawal_subtractsAmountAndAdvancesSequence() {
        BalanceSnapshot folded = BalanceSnapshot.opening(EUR, OPENED_AT)
                .fold(transaction(1, TransactionType.DEPOSIT, "100.00", "100.00"))
                .fold(transaction(2, TransactionType.WITHDRAWAL, "30.00", "70.00"));

        assertThat(folded.availableBalance()).isEqualTo(Money.of("70.00", EUR));
        assertThat(folded.transactionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("folding a run of movements matches folding them one by one")
    void foldAll_listOfMovements_matchesRepeatedFoldCalls() {
        List<Transaction> movements = List.of(
                transaction(1, TransactionType.DEPOSIT, "100.00", "100.00"),
                transaction(2, TransactionType.WITHDRAWAL, "30.00", "70.00"),
                transaction(3, TransactionType.DEPOSIT, "5.50", "75.50"));

        BalanceSnapshot opening = BalanceSnapshot.opening(EUR, OPENED_AT);
        BalanceSnapshot oneByOne = opening;
        for (Transaction movement : movements) {
            oneByOne = oneByOne.fold(movement);
        }

        assertThat(opening.foldAll(movements)).isEqualTo(oneByOne);
    }

    @Test
    @DisplayName("refuses to fold a movement that is not the next in sequence")
    void fold_outOfOrderSequence_throwsIllegalArgumentException() {
        BalanceSnapshot opening = BalanceSnapshot.opening(EUR, OPENED_AT);
        Transaction outOfOrder = transaction(2, TransactionType.DEPOSIT, "100.00", "100.00");

        assertThatIllegalArgumentException().isThrownBy(() -> opening.fold(outOfOrder));
    }

    @Test
    @DisplayName("refuses to fold a movement that was already applied")
    void fold_alreadyAppliedMovement_throwsIllegalArgumentException() {
        Transaction deposit = transaction(1, TransactionType.DEPOSIT, "100.00", "100.00");
        BalanceSnapshot folded = BalanceSnapshot.opening(EUR, OPENED_AT).fold(deposit);

        assertThatIllegalArgumentException().isThrownBy(() -> folded.fold(deposit));
    }

    @Test
    @DisplayName("rejects a marker that disagrees with itself")
    void constructor_inconsistentSequenceAndTransactionIdMarker_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new BalanceSnapshot(Money.zero(EUR), 0L, UUID.randomUUID(), OPENED_AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new BalanceSnapshot(Money.zero(EUR), 1L, null, OPENED_AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new BalanceSnapshot(Money.zero(EUR), -1L, null, OPENED_AT));
    }

    @Test
    @DisplayName("rejects a missing balance or timestamp")
    void constructor_nullBalanceOrTimestamp_throwsNullPointerException() {
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                () -> new BalanceSnapshot(null, 0L, null, OPENED_AT));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                () -> new BalanceSnapshot(Money.zero(EUR), 0L, null, null));
    }
}
