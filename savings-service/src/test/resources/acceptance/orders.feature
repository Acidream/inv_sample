Feature: Orders open and fund savings accounts (ADR 0020)

  Background:
    * hooks.resetClock()
    * url baseUrl
    * def orderId = hooks.uuid()
    * def customerId = hooks.uuid()
    * def currentAccountId = hooks.uuid()
    * def order =
      """
      {
        customerId: '#(customerId)', productCode: 'INSTANT_ACCESS', productVersion: 1, amount: '250.00',
        fromAccountId: '#(currentAccountId)'
      }
      """

  Scenario: an order opens the account on the accepted terms and asks the transfer service for the money
    * def orderKey = orderId
    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 202
    * def orderId = response.orderId
    And match responseHeaders['Location'][0] == '/orders/' + orderId
    And match response contains { orderId: '#(orderId)', status: 'RECEIVED', amount: '250.00' }

    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'FUNDING', accountId: '#uuid', rejectionReason: null }
    * def accountId = response.accountId

    Given path 'accounts', accountId
    When method get
    Then match response ==
      """
      {
        id: '#(accountId)', customerId: '#(customerId)',
        product: {
          code: 'INSTANT_ACCESS', version: 1, name: '#string', annualRate: '0.0250', payoutFrequency: 'MONTHLY'
        },
        currency: 'EUR', balance: '0.00', accruedInterest: '0.0000000000', openedAt: '#string'
      }
      """

    * def opened = hooks.awaitEvent(accountId, 'ACCOUNT_OPENED')
    And match opened contains
      """
      {
        customerId: '#(customerId)', productCode: 'INSTANT_ACCESS', productVersion: 1, annualRate: '0.0250',
        payoutFrequency: 'MONTHLY', orderId: '#(orderId)'
      }
      """

    # The transfer service knows accounts, not products
    * def transfer = hooks.awaitTransferRequest(accountId)
    And match transfer ==
      """
      {
        requestId: '#string', occurredAt: '#string', transferId: '#("savings-order-" + orderId)',
        customerId: '#(customerId)', fromAccountId: '#(currentAccountId)', toAccountId: '#(accountId)',
        amount: { amount: '250.00', currency: 'EUR' }
      }
      """

    # The transfer service moves the money, then reports the outcome once savings has answered
    * hooks.depositTransfer(accountId, transfer.transferId, '250.00')
    * def deposited = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * hooks.transferCompleted(transfer.transferId)
    Given path 'orders', orderId
    And retry until response.status == 'FUNDED'
    When method get
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '250.00'

  Scenario: a failed funding transfer leaves the account open and empty
    * def orderKey = orderId
    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 202
    * def orderId = response.orderId
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    * def accountId = response.accountId

    * def transfer = hooks.awaitTransferRequest(accountId)
    * hooks.transferFailed(transfer.transferId, 'DEBIT_REJECTED', 'overdraft limit exceeded')
    Given path 'orders', orderId
    And retry until response.status == 'FUNDING_FAILED'
    When method get
    Then match response.failureReason == 'DEBIT_REJECTED: overdraft limit exceeded'
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '0.00'

  Scenario: a transfer reported completed without the deposit booked in savings funds nothing
    # Transfer ids are chosen by whoever requests a transfer, so the report alone proves nothing: only
    # the deposit savings booked itself, of the order's amount to the order's account, funds the order.
    * def orderKey = orderId
    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 202
    * def orderId = response.orderId
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    * def accountId = response.accountId
    * def transfer = hooks.awaitTransferRequest(accountId)

    * hooks.transferCompleted(transfer.transferId)
    * def cause = hooks.awaitDeadLetter(transfer.transferId)
    * match cause contains 'IllegalOrderTransitionException'
    Given path 'orders', orderId
    When method get
    Then match response.status == 'FUNDING'

  Scenario: an order for a product that is not on sale is rejected and opens nothing
    * set order.productCode = 'NO_SUCH_PRODUCT'
    * def orderKey = orderId
    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 202
    * def orderId = response.orderId
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'PRODUCT_NOT_ON_SALE', accountId: null }

  Scenario: placing the same order again is safe; a different order under the same id is refused
    * def orderKey = orderId
    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 202
    * def orderId = response.orderId
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    * def accountId = response.accountId

    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 202
    * def orderId = response.orderId
    And match response contains { status: 'FUNDING', accountId: '#(accountId)' }

    * set order.amount = '999.00'
    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 422
    And match response.detail contains 'already placed with different details'

  Scenario: a different order under an id already placed, arriving on the command log, is dead-lettered
    # Two orders under one key can both pass the intake before either is applied; the second must not be
    # dropped silently, its client was told it was accepted.
    * def orderKey = orderId
    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 202
    * def orderId = response.orderId
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    * hooks.placeOrderOnLog(orderId, customerId, '999.00', currentAccountId)
    * def cause = hooks.awaitDeadLetter(orderId)
    * match cause == 'com.example.savings.application.order.OrderIdInUseException'
    Given path 'orders', orderId
    When method get
    Then match response.amount == '250.00'

  Scenario: an order that has not been applied yet is not found
    Given path 'orders', hooks.uuid()
    When method get
    Then status 404

  Scenario: an unknown account is a 404 problem
    Given path 'accounts', hooks.uuid()
    When method get
    Then status 404
    And match response.title == 'Not Found'

  Scenario Outline: invalid orders are refused before they are accepted - <case>
    * set order.<field> = <value>
    * def orderKey = orderId
    Given path 'orders'
    And header Idempotency-Key = orderKey
    And request order
    When method post
    Then status 400

    Examples:
      | case             | field          | value  |
      | missing customer | customerId     | null   |
      | missing product  | productCode    | null   |
      | missing version  | productVersion | null   |
      | zero amount      | amount         | '0.00' |
      | three decimals   | amount         | '1.001'|
      | negative amount  | amount         | '-5'   |
      | missing source   | fromAccountId  | null   |

  Scenario: the API is documented
    Given path 'v3', 'api-docs'
    When method get
    Then status 200
    And match response.paths contains { '/products': '#object', '/orders/{orderId}': '#object', '/accounts/{id}': '#object', '/accounts/{id}/transactions': '#object' }
    And match response.paths['/accounts'] == '#notpresent'
