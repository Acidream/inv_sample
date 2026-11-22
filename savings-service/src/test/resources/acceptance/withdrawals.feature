Feature: Withdrawals go to the customer's current account, debited at once

  # Savings never pays anyone else: money only leaves to the customer's own current account, an
  # internal transfer whose outcome is known. So there is nothing to reserve and nothing to wait for,
  # and the balance can never go negative. External payments are the current account's business.

  Background:
    * hooks.resetClock()
    * def opened = call read('common.feature') { rate: 0.02 }
    * def accountId = opened.accountId
    * url baseUrl
    * hooks.deposit(accountId, '100.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')

  Scenario: a withdrawal debits the account immediately
    * def transferId = hooks.withdraw(accountId, '30.00')
    * def withdrawn = hooks.awaitEvent(accountId, 'MONEY_WITHDRAWN')
    * match withdrawn contains { transferId: '#(transferId)', amount: { amount: '30.00', currency: 'EUR' } }
    * match withdrawn.balance.amount == '70.00'
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '70.00'

  Scenario: a withdrawal larger than the balance is rejected and changes nothing
    * def transferId = hooks.withdraw(accountId, '100.01')
    * def rejected = hooks.awaitEvent(accountId, 'WITHDRAWAL_REJECTED')
    * match rejected == { transferId: '#(transferId)', amount: { amount: '100.01', currency: 'EUR' }, reason: 'INSUFFICIENT_FUNDS', eventId: '#string' }
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '100.00'

  Scenario: the balance is never spent twice
    * def first = hooks.withdraw(accountId, '60.00')
    * def second = hooks.withdraw(accountId, '60.00')
    * def withdrawn = hooks.awaitEvent(accountId, 'MONEY_WITHDRAWN')
    * match withdrawn.transferId == first
    * def rejected = hooks.awaitEvent(accountId, 'WITHDRAWAL_REJECTED')
    * match rejected.transferId == second
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '40.00'

  Scenario: a withdrawal the current account refused is credited back, once
    * def transferId = hooks.withdraw(accountId, '40.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_WITHDRAWN')
    * hooks.returnTransfer(accountId, transferId)
    * hooks.returnTransfer(accountId, transferId)
    # Same key -> same partition -> in order: once this deposit is through, both returns were too.
    * hooks.deposit(accountId, '1.00')
    * def returned = hooks.awaitEvent(accountId, 'WITHDRAWAL_RETURNED')
    * match returned contains { transferId: '#(transferId)', amount: { amount: '40.00', currency: 'EUR' } }
    * match returned.balance.amount == '100.00'
    * def deposited = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * match deposited.balance.amount == '101.00'
    Given path 'accounts', accountId, 'transactions'
    When method get
    Then match response.items[*].type == ['DEPOSIT', 'WITHDRAWAL_RETURN', 'WITHDRAWAL', 'DEPOSIT']

  Scenario: a re-sent withdrawal is applied once
    * def transferId = 'tr-' + hooks.uuid()
    * hooks.withdrawTransfer(accountId, transferId, '30.00')
    * hooks.withdrawTransfer(accountId, transferId, '30.00')
    * hooks.deposit(accountId, '1.00')
    * def withdrawn = hooks.awaitEvent(accountId, 'MONEY_WITHDRAWN')
    * def deposited = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * match deposited.balance.amount == '71.00'

  Scenario: returning a rejected withdrawal violates the contract and is dead-lettered
    * def transferId = hooks.withdraw(accountId, '500.00')
    * def ignored = hooks.awaitEvent(accountId, 'WITHDRAWAL_REJECTED')
    * hooks.returnTransfer(accountId, transferId)
    * def cause = hooks.awaitDeadLetter(accountId)
    * match cause contains 'IllegalTransferException'
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '100.00'

  Scenario: returning an unknown transfer is dead-lettered
    * hooks.returnTransfer(accountId, 'tr-never-sent')
    * def cause = hooks.awaitDeadLetter(accountId)
    * match cause contains 'UnknownTransferException'
