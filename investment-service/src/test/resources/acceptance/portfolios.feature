Feature: Portfolios and instruments (ADR 0026)

  Background:
    * url baseUrl

  Scenario: opening a portfolio publishes it for the transfer service; it starts empty
    * def customerId = hooks.uuid()
    * def currentAccount = hooks.uuid()
    * def key = hooks.uuid()
    Given path 'portfolios'
    And header Idempotency-Key = key
    And request { customerId: '#(customerId)', referenceAccountId: '#(currentAccount)' }
    When method post
    Then status 202
    And match response contains { status: 'RECEIVED', customerId: '#(customerId)', referenceAccountId: '#(currentAccount)' }
    * def portfolioId = response.portfolioId
    And match responseHeaders['Location'][0] == '/portfolios/' + portfolioId

    * def opened = hooks.awaitPortfolioEvent(portfolioId)
    * match opened == { type: 'PORTFOLIO_OPENED', customerId: '#(customerId)', referenceAccountId: '#(currentAccount)' }
    Given path 'portfolios', portfolioId
    And retry until responseStatus == 200
    When method get
    Then match response == { portfolioId: '#(portfolioId)', status: 'OPEN', customerId: '#(customerId)', referenceAccountId: '#(currentAccount)', openedAt: '#string', holdings: [], unpaidPayouts: [] }

    # The same request again is safe; another portfolio under the same key is refused.
    Given path 'portfolios'
    And header Idempotency-Key = key
    And request { customerId: '#(customerId)', referenceAccountId: '#(currentAccount)' }
    When method post
    Then status 202
    And match response.status == 'OPEN'
    Given path 'portfolios'
    And header Idempotency-Key = key
    And request { customerId: '#(customerId)', referenceAccountId: '#(hooks.uuid())' }
    When method post
    Then status 422

  Scenario: an unknown portfolio is a 404 problem
    Given path 'portfolios', hooks.uuid()
    When method get
    Then status 404

  Scenario: opening a portfolio needs an idempotency key and both ids
    Given path 'portfolios'
    And request { customerId: '#(hooks.uuid())', referenceAccountId: '#(hooks.uuid())' }
    When method post
    Then status 400
    Given path 'portfolios'
    And header Idempotency-Key = hooks.uuid()
    And request { customerId: '#(hooks.uuid())' }
    When method post
    Then status 400

  Scenario: instruments on sale; one taken off sale is no longer listed
    Given path 'instruments'
    When method get
    Then status 200
    And match response contains { isin: 'IE00B4L5Y983', name: 'iShares Core MSCI World UCITS ETF (Acc)', type: 'ETF' }
    And match response contains { isin: 'DE0007164600', name: 'SAP SE', type: 'STOCK' }
    And match each response[*].isin != 'DE000BAY0017'
