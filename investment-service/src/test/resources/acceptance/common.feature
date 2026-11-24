@ignore
Feature: Reusable steps

  # Opens a portfolio for a new customer and consumes its opening event.
  # Returns portfolioId, customerId, currentAccount.
  @open
  Scenario: open a portfolio
    * hooks.resetClock()
    * def customerId = hooks.uuid()
    * def currentAccount = hooks.uuid()
    Given url baseUrl
    And path 'portfolios'
    And header Idempotency-Key = hooks.uuid()
    And request { customerId: '#(customerId)', referenceAccountId: '#(currentAccount)' }
    When method post
    Then status 202
    * def portfolioId = response.portfolioId

    Given path 'portfolios', portfolioId
    And retry until responseStatus == 200
    When method get
    * def opened = hooks.awaitPortfolioEvent(portfolioId)
    * match opened == { type: 'PORTFOLIO_OPENED', customerId: '#(customerId)', referenceAccountId: '#(currentAccount)' }

  # Places a buy of `amount` of `isin` on `portfolioId` and plays the transfer service bringing the
  # money, up to the order reaching the broker. Returns orderId, fundingTransferId, brokerOrder.
  @buy
  Scenario: place a funded buy
    Given url baseUrl
    And path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: '#(isin)', side: 'BUY', amount: '#(amount)' }
    When method post
    Then status 202
    * def orderId = response.orderId

    * def funding = hooks.awaitTransferRequest(currentAccount, portfolioId)
    * def fundingTransferId = funding.transferId
    * match fundingTransferId == 'inv-buy-' + orderId
    * hooks.deposit(portfolioId, fundingTransferId, amount)
    * def deposited = hooks.awaitPortfolioEvent(portfolioId)
    * match deposited == { type: 'MONEY_DEPOSITED', transferId: '#(fundingTransferId)', amount: { amount: '#(amount)', currency: 'EUR' } }
    * def brokerOrder = hooks.awaitBrokerOrder(orderId)

  # Buys `quantity` units of `isin` at `price` in one fill, so a feature starts from a holding.
  @holding
  Scenario: buy a holding
    * def amount = karate.get('amount', '1000.00')
    * def bought = call read('common.feature@buy') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: '#(isin)', amount: '#(amount)' }
    * hooks.brokerFilled(bought.orderId, 'buy-' + hooks.uuid(), quantity, price, true)
    Given url baseUrl
    And path 'orders', bought.orderId
    And retry until response.status == 'FILLED'
    When method get
