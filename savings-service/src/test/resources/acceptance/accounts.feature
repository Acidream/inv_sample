Feature: Opening and viewing savings accounts

  Background:
    * hooks.resetClock()
    * url baseUrl

  Scenario: a new account starts empty on the current version of its product and announces itself
    Given path 'accounts'
    And def customerId = hooks.uuid()
    And request { customerId: '#(customerId)', productCode: 'INSTANT_ACCESS' }
    When method post
    Then status 201
    And match responseHeaders['Location'][0] == '/accounts/' + response.id
    And match response ==
      """
      {
        id: '#uuid', customerId: '#(customerId)',
        product: {
          code: 'INSTANT_ACCESS', version: 1, name: '#string', annualRate: '0.0250', payoutFrequency: 'MONTHLY'
        },
        currency: 'EUR', balance: '0.00', accruedInterest: '0.0000000000', openedAt: '#string'
      }
      """
    * def opened = hooks.awaitEvent(response.id, 'ACCOUNT_OPENED')
    And match opened contains
      """
      {
        customerId: '#(customerId)', productCode: 'INSTANT_ACCESS', productVersion: 1, annualRate: '0.0250',
        payoutFrequency: 'MONTHLY'
      }
      """

  Scenario: a retried opening request with the same Idempotency-Key opens one account
    * def customerId = hooks.uuid()
    * def key = hooks.uuid()
    Given path 'accounts'
    And header Idempotency-Key = key
    And request { customerId: '#(customerId)', productCode: 'INSTANT_ACCESS' }
    When method post
    Then status 201
    * def first = response

    Given path 'accounts'
    And header Idempotency-Key = key
    And request { customerId: '#(customerId)', productCode: 'INSTANT_ACCESS' }
    When method post
    Then status 201
    And match response.id == first.id
    And match responseHeaders['Location'][0] == '/accounts/' + first.id

  Scenario: an Idempotency-Key reused for a different request is refused
    * def key = hooks.uuid()
    * def customerId = hooks.uuid()
    Given path 'accounts'
    And header Idempotency-Key = key
    And request { customerId: '#(customerId)', productCode: 'INSTANT_ACCESS' }
    When method post
    Then status 201

    Given path 'accounts'
    And header Idempotency-Key = key
    And request { customerId: '#(customerId)', productCode: 'YEARLY_INTEREST' }
    When method post
    Then status 422
    And match response.detail contains 'already used for a different request'

  Scenario: an account can only be opened on a product that is on sale
    Given path 'accounts'
    And request { customerId: '#(hooks.uuid())', productCode: 'NO_SUCH_PRODUCT' }
    When method post
    Then status 422
    And match response.detail == 'Product NO_SUCH_PRODUCT is not on sale'

    * def productCode = hooks.product(0.02, 'MONTHLY')
    * hooks.stopSelling(productCode)
    Given path 'accounts'
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)' }
    When method post
    Then status 422

  Scenario: an unknown account is a 404 problem
    Given path 'accounts', hooks.uuid()
    When method get
    Then status 404
    And match response.title == 'Not Found'

  Scenario Outline: invalid opening requests are rejected - <case>
    Given path 'accounts'
    And request <body>
    When method post
    Then status 400

    Examples:
      | case             | body                                                                        |
      | missing customer | { productCode: 'INSTANT_ACCESS' }                                           |
      | missing product  | { customerId: '8a3f1c2e-1111-4a2b-9c3d-0e1f2a3b4c5d' }                      |
      | blank product    | { customerId: '8a3f1c2e-1111-4a2b-9c3d-0e1f2a3b4c5d', productCode: ' ' }    |

  Scenario: the API is documented
    Given path 'v3', 'api-docs'
    When method get
    Then status 200
    And match response.paths contains { '/products': '#object', '/accounts': '#object', '/accounts/{id}': '#object', '/accounts/{id}/transactions': '#object' }
