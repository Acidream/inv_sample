Feature: Opening current accounts, incoming payments and blocking (ADR 0021)

  Background:
    * hooks.resetClock()
    * url baseUrl

  Scenario: an account is opened through the command log and announced to the transfer service
    * def accountId = hooks.uuid()
    * def customerId = hooks.uuid()
    * def accountKey = accountId
    Given path 'current-accounts'
    And header Idempotency-Key = accountKey
    And request { customerId: '#(customerId)', overdraftLimit: '500.00' }
    When method post
    Then status 202
    * def accountId = response.accountId
    And match responseHeaders['Location'][0] == '/current-accounts/' + accountId
    And match response contains { accountId: '#(accountId)', status: 'RECEIVED', overdraftLimit: '500.00' }

    Given path 'current-accounts', accountId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'OPEN', customerId: '#(customerId)', balance: '0.00', overdraftLimit: '500.00', available: '500.00', blockReason: null }

    * def opened = hooks.awaitEvent(accountId)
    * match opened contains { type: 'CURRENT_ACCOUNT_OPENED', customerId: '#(customerId)' }

    # Opening again with the same details is a safe retry; the answer is the account as it is.
    Given path 'current-accounts'
    And header Idempotency-Key = accountKey
    And request { customerId: '#(customerId)', overdraftLimit: '500' }
    When method post
    Then status 202
    * def accountId = response.accountId
    And match response.status == 'OPEN'

  Scenario: an idempotency key already used for a different customer is refused
    * def account = call read('common.feature') { overdraftLimit: '0.00' }
    Given path 'current-accounts'
    And header Idempotency-Key = account.accountKey
    And request { customerId: '#(hooks.uuid())', overdraftLimit: '0.00' }
    When method post
    Then status 422

  Scenario: a second opening with different details, arriving on the command log, is dead-lettered
    # Two openings under one key can both pass the intake before either is applied; the second must
    # not be dropped silently, its client was told it was accepted.
    * def account = call read('common.feature') { overdraftLimit: '0.00' }
    * hooks.sendOpening(account.accountId, hooks.uuid(), '100.00')
    * def cause = hooks.awaitDeadLetter('current-account.commands.v1', account.accountId)
    * match cause == 'com.example.currentaccount.application.AccountIdInUseException'

  Scenario: an incoming payment is booked once, however often it is sent
    * def account = call read('common.feature') { overdraftLimit: '0.00' }
    * def accountId = account.accountId
    * def paymentId = 'salary-' + hooks.uuid()
    * def paymentKey = paymentId
    Given path 'current-accounts', accountId, 'incoming-payments'
    And header Idempotency-Key = paymentKey
    And request { amount: '2500.00' }
    When method post
    Then status 202
    * def paymentId = response.paymentId
    And match response contains { paymentId: '#(paymentId)', status: 'RECEIVED', amount: '2500.00' }

    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And retry until responseStatus == 200 && response.status == 'BOOKED'
    When method get

    Given path 'current-accounts', accountId, 'incoming-payments'
    And header Idempotency-Key = paymentKey
    And request { amount: '2500.00' }
    When method post
    Then status 202
    * def paymentId = response.paymentId
    And match response.status == 'BOOKED'

    Given path 'current-accounts', accountId, 'incoming-payments'
    And header Idempotency-Key = paymentKey
    And request { amount: '2600.00' }
    When method post
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
    * def paymentKey = paymentId
    Given path 'current-accounts', accountId, 'incoming-payments'
    And header Idempotency-Key = paymentKey
    And request { amount: '10.00' }
    When method post
    Then status 202
    * def paymentId = response.paymentId
    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'unknown account' }

  Scenario: a payment that would take the balance beyond what the ledger holds is rejected
    * def account = call read('common.feature') { overdraftLimit: '0.00', funds: '99999999999999999.00' }
    Given path 'current-accounts', account.accountId, 'incoming-payments'
    And header Idempotency-Key = hooks.uuid()
    And request { amount: '1.00' }
    When method post
    Then status 202
    * def paymentId = response.paymentId
    Given path 'current-accounts', account.accountId, 'incoming-payments', paymentId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'balance limit exceeded' }
    Given path 'current-accounts', account.accountId
    When method get
    Then match response.balance == '99999999999999999.00'

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
    * def blocked = hooks.awaitEvent(accountId)
    * match blocked contains { type: 'CURRENT_ACCOUNT_BLOCKED', reason: 'suspected fraud' }

    Given path 'current-accounts', accountId, 'block'
    When method delete
    Then status 202
    Given path 'current-accounts', accountId
    And retry until response.status == 'OPEN'
    When method get
    Then match response contains { blockReason: null, available: '150.00' }
    * def unblocked = hooks.awaitEvent(accountId)
    * match unblocked.type == 'CURRENT_ACCOUNT_UNBLOCKED'

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
    Given path 'current-accounts'
    And header Idempotency-Key = hooks.uuid()
    And request <body>
    When method post
    Then status 400

    Examples:
      | case                     | body                                                    |
      | no customer              | { overdraftLimit: '0.00' }                              |
      | negative overdraft limit | { customerId: '#(hooks.uuid())', overdraftLimit: '-1' } |
      | three fraction digits    | { customerId: '#(hooks.uuid())', overdraftLimit: '1.001' } |

  Scenario: a payment of zero is refused
    Given path 'current-accounts', hooks.uuid(), 'incoming-payments'
    And header Idempotency-Key = 'p-1'
    And request { amount: '0.00' }
    When method post
    Then status 400

  Scenario: a malformed command on the log is dead-lettered
    * def accountId = hooks.uuid()
    * hooks.sendPaymentWithOverlongId(accountId)
    * def cause = hooks.awaitDeadLetter('current-account.commands.v1', accountId)
    * match cause == 'com.example.platform.messaging.InvalidMessageException'

  Scenario: a malformed idempotency key is refused before anything is accepted
    Given path 'current-accounts', hooks.uuid(), 'incoming-payments'
    And header Idempotency-Key = 'p 1'
    And request { amount: '1.00' }
    When method post
    Then status 400

  Scenario: a request without an idempotency key is refused, so a retry can never open a second account
    Given path 'current-accounts'
    And request { customerId: '#(hooks.uuid())', overdraftLimit: '0.00' }
    When method post
    Then status 400

  Scenario: a page size out of range is refused
    Given path 'current-accounts', hooks.uuid(), 'bookings'
    And param limit = 0
    When method get
    Then status 400
