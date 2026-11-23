Feature: Opening current accounts, incoming payments and blocking (ADR 0021)

  Background:
    * hooks.resetClock()
    * url baseUrl

  Scenario: an account is opened through the command log and announced to the transfer service
    * def accountId = hooks.uuid()
    * def customerId = hooks.uuid()
    Given path 'current-accounts', accountId
    And request { customerId: '#(customerId)', overdraftLimit: '500.00' }
    When method put
    Then status 202
    And match responseHeaders['Location'][0] == '/current-accounts/' + accountId
    And match response contains { accountId: '#(accountId)', status: 'RECEIVED', overdraftLimit: '500.00' }

    Given path 'current-accounts', accountId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'OPEN', customerId: '#(customerId)', balance: '0.00', overdraftLimit: '500.00', available: '500.00', blockReason: null }

    * def opened = hooks.awaitEvent(accountId)
    * match opened contains { type: 'CURRENT_ACCOUNT_OPENED', customerId: '#(customerId)' }

    # Opening again with the same details is a safe retry; the answer is the account as it is.
    Given path 'current-accounts', accountId
    And request { customerId: '#(customerId)', overdraftLimit: '500' }
    When method put
    Then status 202
    And match response.status == 'OPEN'

  Scenario: an account id already used for a different customer is refused
    * def account = call read('common.feature') { overdraftLimit: '0.00' }
    Given path 'current-accounts', account.accountId
    And request { customerId: '#(hooks.uuid())', overdraftLimit: '0.00' }
    When method put
    Then status 422

  Scenario: an incoming payment is booked once, however often it is sent
    * def account = call read('common.feature') { overdraftLimit: '0.00' }
    * def accountId = account.accountId
    * def paymentId = 'salary-' + hooks.uuid()
    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And request { amount: '2500.00' }
    When method put
    Then status 202
    And match response contains { paymentId: '#(paymentId)', status: 'RECEIVED', amount: '2500.00' }

    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And retry until responseStatus == 200 && response.status == 'BOOKED'
    When method get

    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And request { amount: '2500.00' }
    When method put
    Then status 202
    And match response.status == 'BOOKED'

    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And request { amount: '2600.00' }
    When method put
    Then status 422

    Given path 'current-accounts', accountId
    When method get
    Then match response contains { balance: '2500.00', available: '2500.00' }
    Given path 'current-accounts', accountId, 'bookings'
    When method get
    Then match response == [{ reference: '#(paymentId)', type: 'INCOMING_PAYMENT', amount: '2500.00', balanceAfter: '2500.00', bookedAt: '#string' }]

  Scenario: a payment for an account that doesn't exist is rejected
    * def accountId = hooks.uuid()
    * def paymentId = 'pay-' + hooks.uuid()
    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And request { amount: '10.00' }
    When method put
    Then status 202
    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'unknown account' }

  Scenario: a blocked account shows nothing available until it is unblocked
    * def account = call read('common.feature') { overdraftLimit: '100.00', funds: '50.00' }
    * def accountId = account.accountId
    Given path 'current-accounts', accountId, 'block'
    And request { reason: 'suspected fraud' }
    When method put
    Then status 202
    Given path 'current-accounts', accountId
    And retry until response.status == 'BLOCKED'
    When method get
    Then match response contains { blockReason: 'suspected fraud', balance: '50.00', available: '0.00' }

    Given path 'current-accounts', accountId, 'block'
    When method delete
    Then status 202
    Given path 'current-accounts', accountId
    And retry until response.status == 'OPEN'
    When method get
    Then match response contains { blockReason: null, available: '150.00' }

  Scenario: blocking an account that doesn't exist is refused
    Given path 'current-accounts', hooks.uuid(), 'block'
    And request { reason: 'test' }
    When method put
    Then status 404

  Scenario: an unknown account is not found
    Given path 'current-accounts', hooks.uuid()
    When method get
    Then status 404

  Scenario Outline: invalid requests are refused before anything is accepted: <case>
    Given path 'current-accounts', hooks.uuid()
    And request <body>
    When method put
    Then status 400

    Examples:
      | case                     | body                                                    |
      | no customer              | { overdraftLimit: '0.00' }                              |
      | negative overdraft limit | { customerId: '#(hooks.uuid())', overdraftLimit: '-1' } |
      | three fraction digits    | { customerId: '#(hooks.uuid())', overdraftLimit: '1.001' } |

  Scenario: a payment of zero is refused
    Given path 'current-accounts', hooks.uuid(), 'incoming-payments', 'p-1'
    And request { amount: '0.00' }
    When method put
    Then status 400

  Scenario: a malformed command on the log is dead-lettered
    * def accountId = hooks.uuid()
    * hooks.sendPaymentWithOverlongId(accountId)
    * def cause = hooks.awaitDeadLetter('current-account.commands.v1', accountId)
    * match cause == 'java.lang.IllegalArgumentException'

  Scenario: a malformed payment id in the path is refused before anything is accepted
    Given path 'current-accounts', hooks.uuid(), 'incoming-payments', 'p!1'
    And request { amount: '1.00' }
    When method put
    Then status 400

  Scenario: a page size out of range is refused
    Given path 'current-accounts', hooks.uuid(), 'bookings'
    And param limit = 0
    When method get
    Then status 400
