Feature: Recording money movements
  As an account holder
  I want to pay money in and take money out
  So that the ledger reflects what actually happened to my money

  Background:
    Given an account "Ada" in EUR with an overdraft allowance of 100.00

  Scenario: Money paid in increases the balance
    When I deposit 250.00 EUR into "Ada" with the reference "Salary"
    Then the deposit is accepted
    And the available balance of "Ada" is 250.00 EUR

  Scenario: Money taken out decreases the balance
    Given "Ada" has already received a deposit of 250.00 EUR
    When I withdraw 40.00 EUR from "Ada"
    Then the withdrawal is accepted
    And the available balance of "Ada" is 210.00 EUR

  Scenario: The account balance includes the overdraft allowance
    Given "Ada" has already received a deposit of 250.00 EUR
    Then the account balance of "Ada" is 350.00 EUR
    And the overdraft allowance of "Ada" is 100.00 EUR

  Scenario: The overdraft allowance is not a deposit
    Then the transaction history of "Ada" is empty
    And the available balance of "Ada" is 0.00 EUR

  Scenario: Spending into the agreed overdraft is allowed
    When I withdraw 100.00 EUR from "Ada"
    Then the withdrawal is accepted
    And the available balance of "Ada" is -100.00 EUR
    And the account balance of "Ada" is 0.00 EUR

  Scenario: Spending beyond the agreed overdraft is refused
    When I withdraw 100.01 EUR from "Ada"
    Then the withdrawal is refused because of insufficient funds
    And the available balance of "Ada" is 0.00 EUR
    And the transaction history of "Ada" is empty

  Scenario: Money in the wrong currency is refused
    When I deposit 50.00 GBP into "Ada"
    Then the movement is refused because of a currency mismatch

  Scenario Outline: An amount that is not strictly positive is refused
    When I deposit <amount> EUR into "Ada"
    Then the movement is rejected as invalid

    Examples:
      | amount |
      | 0.00   |
      | -5.00  |

  Scenario: A movement captured earlier is accepted and keeps its own event time
    When I deposit 250.00 EUR into "Ada" with the reference "Salary", claiming it happened 6 hours ago
    Then the deposit is accepted
    And the available balance of "Ada" is 250.00 EUR

  Scenario: A client's clock cannot reorder the statement
    When I deposit 10.00 EUR into "Ada" with the reference "First", claiming it happened 1 hours ago
    And I deposit 20.00 EUR into "Ada" with the reference "Second", claiming it happened 10 hours ago
    And I deposit 30.00 EUR into "Ada" with the reference "Third", claiming it happened 20 hours ago
    And I view the transaction history of "Ada"
    Then the references in the history are Third, Second, First
    And the available balance of "Ada" is 60.00 EUR

  Scenario: A movement dated implausibly far in the past is refused
    When I deposit 50.00 EUR into "Ada", claiming it happened 3 days ago
    Then the movement is rejected as invalid
    And the transaction history of "Ada" is empty

  Scenario: A movement dated in the future is refused
    When I deposit 50.00 EUR into "Ada", claiming it will happen in 2 hours
    Then the movement is rejected as invalid
    And the transaction history of "Ada" is empty

  Scenario: Retrying a deposit with the same idempotency key does not double the balance
    When I deposit 250.00 EUR into "Ada" using idempotency key "salary-key-1"
    Then the deposit is accepted
    And the available balance of "Ada" is 250.00 EUR
    When I retry that same request
    Then the deposit is accepted
    And the available balance of "Ada" is 250.00 EUR
    When I view the transaction history of "Ada"
    Then the history reports a total of 1 movements
