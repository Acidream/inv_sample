Feature: Opening and viewing savings accounts

  Background:
    * hooks.resetClock()
    * url baseUrl

  Scenario: a new account starts empty and announces itself
    Given path 'accounts'
    And def customerId = hooks.uuid()
    And request { customerId: '#(customerId)', annualRate: 0.025 }
    When method post
    Then status 201
    And match responseHeaders['Location'][0] == '/accounts/' + response.id
    And match response ==
      """
      {
        id: '#uuid', customerId: '#(customerId)', annualRate: '0.0250', currency: 'EUR',
        balance: '0.00',
        accruedInterest: '0.0000000000', openedAt: '#string'
      }
      """
    * def opened = hooks.awaitEvent(response.id, 'ACCOUNT_OPENED')
    And match opened.customerId == customerId
    And match opened.annualRate == '0.0250'

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
      | case             | body                                                                         |
      | missing customer | { annualRate: 0.01 }                                                         |
      | negative rate    | { customerId: '8a3f1c2e-1111-4a2b-9c3d-0e1f2a3b4c5d', annualRate: -0.01 }    |
      | absurd rate      | { customerId: '8a3f1c2e-1111-4a2b-9c3d-0e1f2a3b4c5d', annualRate: 0.5 }      |
      | too many digits  | { customerId: '8a3f1c2e-1111-4a2b-9c3d-0e1f2a3b4c5d', annualRate: 0.012345 } |

  Scenario: the API is documented
    Given path 'v3', 'api-docs'
    When method get
    Then status 200
    And match response.paths contains { '/accounts': '#object', '/accounts/{id}': '#object', '/accounts/{id}/transactions': '#object' }
