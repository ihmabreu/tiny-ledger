Feature: Viewing the transaction history
  As an account holder
  I want to see what has happened to my money, newest first
  So that I can reconcile my balance against the movements behind it

  Background:
    Given an account "Grace" in GBP with an overdraft allowance of 0.00

  Scenario: A new account has no history
    Then the transaction history of "Grace" is empty

  Scenario: Movements are listed most recent first
    Given the following movements have been recorded for "Grace":
      | type       | amount | reference |
      | DEPOSIT    | 500.00 | Salary    |
      | WITHDRAWAL | 120.00 | Rent      |
      | DEPOSIT    | 25.50  | Refund    |
    When I view the transaction history of "Grace"
    Then the history shows 3 movements
    And the references in the history are "Refund", "Rent", "Salary"

  Scenario: Each movement records the balance it produced
    Given the following movements have been recorded for "Grace":
      | type       | amount | reference |
      | DEPOSIT    | 500.00 | Salary    |
      | WITHDRAWAL | 120.00 | Rent      |
    When I view the transaction history of "Grace"
    Then the most recent movement shows a resulting balance of 380.00 GBP

  Scenario: The history can be paged
    Given the following movements have been recorded for "Grace":
      | type    | amount | reference |
      | DEPOSIT | 1.00   | one       |
      | DEPOSIT | 2.00   | two       |
      | DEPOSIT | 3.00   | three     |
      | DEPOSIT | 4.00   | four      |
    When I view the transaction history of "Grace" with limit 2 and offset 1
    Then the history shows 2 movements
    And the history reports a total of 4 movements
    And the references in the history are "three", "two"

  Scenario: The balance always equals the sum of the history
    Given the following movements have been recorded for "Grace":
      | type       | amount | reference |
      | DEPOSIT    | 500.00 | Salary    |
      | WITHDRAWAL | 120.00 | Rent      |
      | DEPOSIT    | 25.50  | Refund    |
    Then the available balance of "Grace" equals the sum of its transaction history
