Feature: Two-phase withdrawals (reserve, then confirm or cancel)

  Background:
    * hooks.resetClock()
    * def opened = call read('common.feature') { rate: 0.02 }
    * def accountId = opened.accountId
    * url baseUrl
    * hooks.deposit(accountId, '100.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')

  Scenario: reserve then confirm debits the account
    * def requestId = hooks.requestWithdrawal(accountId, '30.00')
    * def reserved = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')
    * match reserved.requestId == requestId
    * match reserved.availableBalance.amount == '70.00'
    Given path 'accounts', accountId
    When method get
    Then match response contains { balance: '100.00', heldAmount: '30.00', availableBalance: '70.00' }

    * hooks.confirmWithdrawal(accountId, requestId)
    * def withdrawn = hooks.awaitEvent(accountId, 'MONEY_WITHDRAWN')
    * match withdrawn.balance.amount == '70.00'
    Given path 'accounts', accountId
    When method get
    Then match response contains { balance: '70.00', heldAmount: '0.00', availableBalance: '70.00' }

  Scenario: a withdrawal larger than the available balance is rejected
    * def requestId = hooks.requestWithdrawal(accountId, '100.01')
    * def rejected = hooks.awaitEvent(accountId, 'WITHDRAWAL_REJECTED')
    * match rejected == { requestId: '#(requestId)', amount: { amount: '100.01', currency: 'EUR' }, reason: 'INSUFFICIENT_FUNDS', eventId: '#string' }

  Scenario: held funds cannot be withdrawn twice
    * def first = hooks.requestWithdrawal(accountId, '60.00')
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')
    * def second = hooks.requestWithdrawal(accountId, '60.00')
    * def rejected = hooks.awaitEvent(accountId, 'WITHDRAWAL_REJECTED')
    * match rejected.requestId == second

  Scenario: a cancelled withdrawal releases the funds
    * def requestId = hooks.requestWithdrawal(accountId, '40.00')
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')
    * hooks.cancelWithdrawal(accountId, requestId)
    * def released = hooks.awaitEvent(accountId, 'FUNDS_RELEASED')
    * match released.reason == 'CANCELLED'
    Given path 'accounts', accountId
    When method get
    Then match response contains { balance: '100.00', heldAmount: '0.00', availableBalance: '100.00' }

  Scenario: confirming a cancelled withdrawal violates the contract and is dead-lettered
    * def requestId = hooks.requestWithdrawal(accountId, '40.00')
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')
    * hooks.cancelWithdrawal(accountId, requestId)
    * def released = hooks.awaitEvent(accountId, 'FUNDS_RELEASED')
    * hooks.confirmWithdrawal(accountId, requestId)
    * def cause = hooks.awaitDeadLetter(accountId)
    * match cause contains 'IllegalHoldTransitionException'
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '100.00'
