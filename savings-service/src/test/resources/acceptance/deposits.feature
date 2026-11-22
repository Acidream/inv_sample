Feature: Deposits arrive from Payments via Kafka

  Background:
    * hooks.resetClock()
    * def opened = call read('common.feature') { rate: 0.02 }
    * def accountId = opened.accountId
    * url baseUrl

  Scenario: a received payment is credited and published
    * hooks.deposit(accountId, '100.00')
    * def deposited = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * match deposited.amount == { amount: '100.00', currency: 'EUR' }
    * match deposited.balance.amount == '100.00'
    Given path 'accounts', accountId
    When method get
    Then status 200
    And match response.balance == '100.00'
    And match response.availableBalance == '100.00'

  Scenario: a redelivered message is applied only once
    * def eventId = hooks.uuid()
    * hooks.depositWithEventId(accountId, '100.00', eventId)
    * hooks.depositWithEventId(accountId, '100.00', eventId)
    # Same key -> same partition -> processed in order: once this one is through, the duplicate was too.
    * hooks.deposit(accountId, '1.00')
    * def first = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * def second = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * match second.balance.amount == '101.00'
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '101.00'

  Scenario: the same payment re-sent as a new message is credited only once
    * def paymentId = 'pay-' + hooks.uuid()
    * hooks.depositPayment(accountId, paymentId, '100.00')
    * hooks.depositPayment(accountId, paymentId, '100.00')
    * hooks.deposit(accountId, '1.00')
    * def first = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * match first.paymentId == paymentId
    * def second = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * match second.paymentId != paymentId
    * match second.balance.amount == '101.00'
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '101.00'

  Scenario: a payment id reused for a different amount violates the contract and is dead-lettered
    * def paymentId = 'pay-' + hooks.uuid()
    * hooks.depositPayment(accountId, paymentId, '100.00')
    * def credited = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * hooks.depositPayment(accountId, paymentId, '999.00')
    * def cause = hooks.awaitDeadLetter(accountId)
    * match cause contains 'IllegalArgumentException'
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '100.00'

  Scenario: the statement is paginated newest first with an opaque cursor
    * hooks.deposit(accountId, '10.00')
    * hooks.deposit(accountId, '20.00')
    * hooks.deposit(accountId, '30.00')
    Given path 'accounts', accountId
    And retry until response.balance == '60.00'
    When method get

    Given path 'accounts', accountId, 'transactions'
    And param limit = 2
    When method get
    Then status 200
    And match response.items[*].amount == ['30.00', '20.00']
    And match response.items[*].balanceAfter == ['60.00', '30.00']
    And match each response.items contains { type: 'DEPOSIT' }
    And match response.nextCursor == '#string'

    Given path 'accounts', accountId, 'transactions'
    And param limit = 2
    And param cursor = response.nextCursor
    When method get
    Then status 200
    And match response.items[*].amount == ['10.00']
    And match response.nextCursor == null

  Scenario: a garbage cursor is a client error
    Given path 'accounts', accountId, 'transactions'
    And param cursor = 'not-a-cursor'
    When method get
    Then status 400
