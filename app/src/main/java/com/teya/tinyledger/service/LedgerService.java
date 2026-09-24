package com.teya.tinyledger.service;

import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.AccountBalance;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionPage;
import com.teya.tinyledger.domain.TransactionType;
import com.teya.tinyledger.domain.exception.AccountNotFoundException;
import com.teya.tinyledger.domain.exception.CurrencyMismatchException;
import com.teya.tinyledger.domain.exception.InsufficientFundsException;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * The ledger's use cases: opening accounts, recording money movements, and reading balances
 * and history.
 *
 * <p>Declared as an interface so that the HTTP layer depends on this contract rather than on a
 * concrete implementation, which keeps the resources trivially testable in isolation.</p>
 */
public interface LedgerService {

    /** Number of transactions returned when a caller does not ask for a specific page size. */
    int DEFAULT_PAGE_SIZE = 20;

    /** Largest page of transactions a caller may request in one go. */
    int MAX_PAGE_SIZE = 100;

    /**
     * Opens a new account.
     *
     * <p>The account starts empty; opening funds are added with an ordinary deposit.</p>
     *
     * @param ownerName      name of the account holder
     * @param currency       the currency the account is held in, fixed for its lifetime
     * @param overdraftLimit the agreed, non-negative overdraft allowance in the account currency
     * @return the newly opened account
     * @throws IllegalArgumentException  if the owner name is blank or the limit is negative
     * @throws CurrencyMismatchException if the overdraft limit is in another currency
     */
    Account openAccount(String ownerName, Currency currency, Money overdraftLimit);

    /**
     * Looks up an account.
     *
     * @param accountId the identifier to resolve
     * @return the account
     * @throws AccountNotFoundException if no such account exists
     */
    Account getAccount(UUID accountId);

    /**
     * Lists every account known to the ledger.
     *
     * @return all accounts, oldest first
     */
    List<Account> listAccounts();

    /**
     * Records money paid into an account.
     *
     * @param accountId the account to credit
     * @param amount    the strictly positive amount, in the account currency
     * @param reference an optional free-text note, may be {@code null}
     * @return the recorded transaction
     * @throws AccountNotFoundException  if no such account exists
     * @throws CurrencyMismatchException if the amount is in another currency
     * @throws IllegalArgumentException  if the amount is not strictly positive
     */
    Transaction deposit(UUID accountId, Money amount, String reference);

    /**
     * Records money taken out of an account.
     *
     * @param accountId the account to debit
     * @param amount    the strictly positive amount, in the account currency
     * @param reference an optional free-text note, may be {@code null}
     * @return the recorded transaction
     * @throws AccountNotFoundException   if no such account exists
     * @throws CurrencyMismatchException  if the amount is in another currency
     * @throws IllegalArgumentException   if the amount is not strictly positive
     * @throws InsufficientFundsException if the withdrawal would breach the overdraft allowance
     */
    Transaction withdraw(UUID accountId, Money amount, String reference);

    /**
     * Records a money movement of the given direction.
     *
     * @param accountId the account to move money on
     * @param type      whether money moves in or out
     * @param amount    the strictly positive amount, in the account currency
     * @param reference an optional free-text note, may be {@code null}
     * @return the recorded transaction
     * @throws AccountNotFoundException   if no such account exists
     * @throws CurrencyMismatchException  if the amount is in another currency
     * @throws IllegalArgumentException   if the amount is not strictly positive
     * @throws InsufficientFundsException if the movement would breach the overdraft allowance
     */
    Transaction recordMovement(UUID accountId, TransactionType type, Money amount, String reference);

    /**
     * Reads an account's current balance.
     *
     * @param accountId the account to inspect
     * @return the available balance, the account balance and the overdraft allowance
     * @throws AccountNotFoundException if no such account exists
     */
    AccountBalance getBalance(UUID accountId);

    /**
     * Reads a page of an account's transaction history, most recent movement first.
     *
     * @param accountId the account to inspect
     * @param limit     the page size, or {@code null} for {@value #DEFAULT_PAGE_SIZE}
     * @param offset    how many of the most recent transactions to skip, or {@code null} for none
     * @return the requested page
     * @throws AccountNotFoundException if no such account exists
     * @throws IllegalArgumentException if the page size is outside 1..{@value #MAX_PAGE_SIZE},
     *                                  or the offset is negative
     */
    TransactionPage getHistory(UUID accountId, Integer limit, Integer offset);
}
