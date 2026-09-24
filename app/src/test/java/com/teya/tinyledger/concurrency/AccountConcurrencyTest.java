package com.teya.tinyledger.concurrency;

import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.BalanceSnapshot;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionType;
import com.teya.tinyledger.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress tests proving the {@link Account} aggregate is safe under heavy contention.
 *
 * <p>A ledger that loses a single movement under load is worse than useless, and that class of
 * bug is invisible to single-threaded tests. Every scenario here therefore runs thousands of
 * operations on virtual threads, released simultaneously by a latch so they genuinely collide,
 * and then asserts the invariants that must survive:</p>
 *
 * <ul>
 *   <li>no update is lost &mdash; the balance reflects every accepted movement;</li>
 *   <li>the incrementally maintained snapshot still equals the sum of the visible history;</li>
 *   <li>the overdraft allowance is never breached, not even momentarily;</li>
 *   <li>movement sequence numbers are unique and contiguous.</li>
 * </ul>
 */
@Tag("concurrency")
@DisplayName("Account under concurrent load")
class AccountConcurrencyTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final int THREADS = 256;
    private static final int MOVEMENTS_PER_THREAD = 40;

    private static Account newAccount(String overdraftLimit) {
        return Account.open("Concurrent Owner", EUR, Money.of(overdraftLimit, EUR), Clock.systemUTC());
    }

    /**
     * Runs the supplied task once per virtual thread, releasing them all at the same instant.
     *
     * @param threads how many virtual threads to start
     * @param task    the work each thread performs
     * @param <T>     the task result type
     * @return every task's result, in submission order
     */
    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(threads);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
        }

        List<T> results = new ArrayList<>(threads);
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("loses no deposit when thousands are recorded at once")
    void recordMovement_concurrentDeposits_losesNoUpdates() throws Exception {
        Account account = newAccount("0.00");

        runConcurrently(THREADS, () -> {
            for (int i = 0; i < MOVEMENTS_PER_THREAD; i++) {
                account.recordMovement(TransactionType.DEPOSIT, Money.of("1.00", EUR), null);
            }
            return null;
        });

        int expectedMovements = THREADS * MOVEMENTS_PER_THREAD;
        assertThat(account.balance().availableBalance())
                .isEqualTo(Money.of(expectedMovements + ".00", EUR));
        assertThat(account.history(1, 0).total()).isEqualTo(expectedMovements);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("keeps the balance equal to the sum of the history under mixed traffic")
    void recordMovement_concurrentMixedMovements_maintainsBalanceEqualToHistorySum() throws Exception {
        Account account = newAccount("1000000.00");

        runConcurrently(THREADS, () -> {
            for (int i = 0; i < MOVEMENTS_PER_THREAD; i++) {
                TransactionType type = (i % 2 == 0) ? TransactionType.DEPOSIT : TransactionType.WITHDRAWAL;
                account.recordMovement(type, Money.of("3.33", EUR), null);
            }
            return null;
        });

        Account.ConsistentView view = account.consistentView();
        Money replayed = view.transactions().stream()
                .map(Transaction::signedAmount)
                .reduce(Money.zero(EUR), Money::add);

        assertThat(view.balanceSnapshot().availableBalance()).isEqualTo(replayed);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("never lets the balance fall past the overdraft allowance")
    void recordMovement_concurrentWithdrawalsExceedingAllowance_enforcesExactLimitWithoutBreach() throws Exception {
        Account account = newAccount("100.00");
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        // Far more withdrawals are attempted than the allowance can fund, so the aggregate has
        // to arbitrate: exactly 100 single-euro withdrawals may succeed, and not one more.
        runConcurrently(THREADS, () -> {
            for (int i = 0; i < MOVEMENTS_PER_THREAD; i++) {
                try {
                    account.recordMovement(TransactionType.WITHDRAWAL, Money.of("1.00", EUR), null);
                    accepted.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    refused.incrementAndGet();
                }
            }
            return null;
        });

        assertThat(accepted.get()).isEqualTo(100);
        assertThat(refused.get()).isEqualTo(THREADS * MOVEMENTS_PER_THREAD - 100);
        assertThat(account.balance().availableBalance()).isEqualTo(Money.of("-100.00", EUR));
        assertThat(account.balance().accountBalance()).isEqualTo(Money.zero(EUR));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("assigns every movement a unique, contiguous sequence number")
    void recordMovement_concurrentDeposits_assignsUniqueAndContiguousSequenceNumbers() throws Exception {
        Account account = newAccount("0.00");

        runConcurrently(THREADS, () -> {
            for (int i = 0; i < MOVEMENTS_PER_THREAD; i++) {
                account.recordMovement(TransactionType.DEPOSIT, Money.of("1.00", EUR), null);
            }
            return null;
        });

        List<Transaction> transactions = account.consistentView().transactions();
        List<Long> sequences = transactions.stream().map(Transaction::sequence).toList();

        assertThat(sequences).doesNotHaveDuplicates();
        assertThat(sequences).isSorted();
        assertThat(sequences.getFirst()).isEqualTo(1L);
        assertThat(sequences.getLast()).isEqualTo((long) transactions.size());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("records a resulting balance on each movement that matches a replay of history")
    void recordMovement_concurrentMixedMovements_recordedBalanceMatchesHistoryReplay() throws Exception {
        Account account = newAccount("500.00");

        runConcurrently(THREADS, () -> {
            for (int i = 0; i < 10; i++) {
                account.recordMovement(TransactionType.DEPOSIT, Money.of("2.00", EUR), null);
                account.recordMovement(TransactionType.WITHDRAWAL, Money.of("1.00", EUR), null);
            }
            return null;
        });

        BalanceSnapshot replayed = BalanceSnapshot
                .opening(EUR, account.openedAt())
                .foldAll(account.consistentView().transactions());

        assertThat(replayed.availableBalance())
                .isEqualTo(account.balance().availableBalance());

        // Each movement's recorded "balance after" must equal the running total at that point,
        // which is what makes the history independently auditable line by line.
        Money running = Money.zero(EUR);
        for (Transaction transaction : account.consistentView().transactions()) {
            running = running.add(transaction.signedAmount());
            assertThat(transaction.availableBalanceAfter()).isEqualTo(running);
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("serves consistent reads while movements are being recorded")
    void consistentView_concurrentWithMovements_servesConsistentSnapshotWithoutTornReads() throws Exception {
        Account account = newAccount("0.00");
        int writers = 64;
        int readers = 64;
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < writers; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    for (int j = 0; j < MOVEMENTS_PER_THREAD; j++) {
                        account.recordMovement(TransactionType.DEPOSIT, Money.of("1.00", EUR), null);
                    }
                    return null;
                }));
            }
            for (int i = 0; i < readers; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    for (int j = 0; j < MOVEMENTS_PER_THREAD; j++) {
                        Account.ConsistentView view = account.consistentView();
                        Money replayed = view.transactions().stream()
                                .map(Transaction::signedAmount)
                                .reduce(Money.zero(EUR), Money::add);
                        // A reader must never observe a balance that disagrees with the
                        // history it was handed at the very same moment.
                        assertThat(view.balanceSnapshot().availableBalance()).isEqualTo(replayed);
                    }
                    return null;
                }));
            }
            startGate.countDown();
        }

        for (Future<?> future : futures) {
            future.get();
        }

        assertThat(account.balance().availableBalance())
                .isEqualTo(Money.of(writers * MOVEMENTS_PER_THREAD + ".00", EUR));
    }
}
