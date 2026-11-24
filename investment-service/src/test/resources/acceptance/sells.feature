Feature: Selling by quantity, first in, first out (ADR 0026)

  Background:
    * url baseUrl
    * def portfolio = call read('common.feature@open')
    * def portfolioId = portfolio.portfolioId
    * def currentAccount = portfolio.currentAccount
    * def isin = 'DE0007164600'
    # Two lots: 2 units at 100.00, then 2 units at 150.00. Each buy spends exactly what it brought.
    * call read('common.feature@holding') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: '#(isin)', amount: '200.00', quantity: '2', price: '100.0000' }
    * call read('common.feature@holding') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: '#(isin)', amount: '300.00', quantity: '2', price: '150.0000' }

  Scenario: a sell takes the oldest lots first; its gain is what it brought minus what they cost
    Given path 'portfolios', portfolioId
    When method get
    Then match response.holdings == [{ isin: '#(isin)', quantity: '4.000000', reservedForSale: '0.000000', costBasis: '500.00', lastPrice: '150.0000' }]

    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: '#(isin)', side: 'SELL', quantity: '3' }
    When method post
    Then status 202
    * def orderId = response.orderId
    * def placed = hooks.awaitBrokerOrder(orderId)
    * match placed == { orderId: '#(orderId)', isin: '#(isin)', side: 'SELL', quantity: '3.000000', tradingDay: '#string' }
    Given path 'portfolios', portfolioId
    When method get
    Then match response.holdings[0] contains { quantity: '4.000000', reservedForSale: '3.000000' }

    # 3 units at 160.00 = 480.00; FIFO cost: 2 x 100.00 + 1 x 150.00 = 350.00; gain 130.00.
    * hooks.brokerFilled(orderId, 's1', '3', '160.0000', true)
    * def proceeds = hooks.awaitTransferRequest(portfolioId, currentAccount)
    * match proceeds.amount.amount == '480.00'
    Given path 'orders', orderId
    When method get
    Then match response contains { status: 'FILLED', settlement: 'PENDING', filledAmount: '480.00' }
    And match response.fills[0] contains { amount: '480.00', realizedGain: '130.00' }

    Given path 'portfolios', portfolioId
    When method get
    Then match response.holdings == [{ isin: '#(isin)', quantity: '1.000000', reservedForSale: '0.000000', costBasis: '150.00', lastPrice: '160.0000' }]
    Given path 'portfolios', portfolioId, 'transactions'
    When method get
    Then match response.transactions[0] contains { type: 'SELL', amount: '480.00', balanceAfter: '480.00', quantity: '3.000000', realizedGain: '130.00' }

    * def answer = hooks.payOut(portfolioId, proceeds.transferId, '480.00')
    * match answer.type == 'MONEY_WITHDRAWN'
    Given path 'orders', orderId
    And retry until response.settlement == 'SETTLED'
    When method get

  Scenario: units reserved by an open sell can't be sold twice
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: '#(isin)', side: 'SELL', quantity: '3' }
    When method post
    * def first = response.orderId
    * def placed = hooks.awaitBrokerOrder(first)

    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: '#(isin)', side: 'SELL', quantity: '1.5' }
    When method post
    * def second = response.orderId
    Given path 'orders', second
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'not enough free units', settlement: 'SETTLED' }

  Scenario: an expired sell gives back the units it didn't sell and pays out what it did
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: '#(isin)', side: 'SELL', quantity: '4' }
    When method post
    * def orderId = response.orderId
    * def placed = hooks.awaitBrokerOrder(orderId)
    # 0.333333 of the first lot: its cost is 200.00 x 0.333333 / 2 = 33.3333; at 99.99 that is 33.33, a loss of 0.00.
    * hooks.brokerFilled(orderId, 'q1', '0.333333', '99.9900', false)
    * hooks.brokerExpired(orderId)
    * def proceeds = hooks.awaitTransferRequest(portfolioId, currentAccount)
    * match proceeds.amount.amount == '33.33'
    Given path 'orders', orderId
    When method get
    Then match response contains { status: 'EXPIRED', filledQuantity: '0.333333' }
    And match response.fills[0].realizedGain == '0.00'
    Given path 'portfolios', portfolioId
    When method get
    Then match response.holdings[0] contains { quantity: '3.666667', reservedForSale: '0.000000', costBasis: '466.67' }

  Scenario: selling an instrument the portfolio doesn't hold is rejected
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: 'IE00B5BMR087', side: 'SELL', quantity: '1' }
    When method post
    * def orderId = response.orderId
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'not enough free units' }

  Scenario: an instrument taken off sale can still be sold
    * call read('common.feature@holding') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: 'DE0005557508', amount: '25.00', quantity: '1', price: '25.0000' }
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: 'DE0005557508', side: 'SELL', quantity: '1' }
    When method post
    * def placed = hooks.awaitBrokerOrder(response.orderId)
    * match placed.side == 'SELL'

  Scenario Outline: a sell names a quantity and no amount - <case>
    * def body = { portfolioId: '#(portfolioId)', isin: '#(isin)', side: 'SELL', quantity: '1' }
    * set body.<field> = <value>
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request body
    When method post
    Then status 400

    Examples:
      | case           | field    | value       |
      | no quantity    | quantity | null        |
      | zero           | quantity | '0'         |
      | seven decimals | quantity | '0.0000001' |
      | with an amount | amount   | '10.00'     |
