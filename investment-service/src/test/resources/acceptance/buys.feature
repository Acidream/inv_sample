Feature: Buying by amount, money first (ADR 0026)

  Background:
    * url baseUrl
    * def portfolio = call read('common.feature@open')
    * def portfolioId = portfolio.portfolioId
    * def currentAccount = portfolio.currentAccount

  Scenario: a buy pulls its money from the current account, then goes to the broker; a full fill settles it
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: 'IE00B4L5Y983', side: 'BUY', amount: '500.00' }
    When method post
    Then status 202
    And match response contains { status: 'RECEIVED', side: 'BUY', amount: '500.00', quantity: null }
    * def orderId = response.orderId
    And match orderId == '#regex ord-[0-9a-f-]{36}'
    And match responseHeaders['Location'][0] == '/orders/' + orderId

    * def funding = hooks.awaitTransferRequest(currentAccount, portfolioId)
    * match funding contains { transferId: '#("inv-buy-" + orderId)', customerId: '#(portfolio.customerId)', amount: { amount: '500.00', currency: 'EUR' } }
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'FUNDING', settlement: 'PENDING' }

    * hooks.deposit(portfolioId, funding.transferId, '500.00')
    * def deposited = hooks.awaitPortfolioEvent(portfolioId)
    * match deposited.type == 'MONEY_DEPOSITED'
    * def placed = hooks.awaitBrokerOrder(orderId)
    * match placed == { orderId: '#(orderId)', isin: 'IE00B4L5Y983', side: 'BUY', cashAmount: { amount: '500.00', currency: 'EUR' }, tradingDay: '#string' }
    * hooks.transferCompleted(funding.transferId)

    * hooks.brokerAccepted(orderId)
    * hooks.brokerFilled(orderId, 'e1', '3.125', '96.0000', false)
    * hooks.brokerFilled(orderId, 'e2', '2.083333', '96.0000', true)
    Given path 'orders', orderId
    And retry until response.status == 'FILLED'
    When method get
    # 3.125 x 96 = 300.00; 2.083333 x 96 = 199.999968, rounded half-even to 200.00: exactly the 500.00 the order brought.
    Then match response contains { settlement: 'SETTLED', filledQuantity: '5.208333', filledAmount: '500.00' }
    And match response.fills == [{ execId: 'e1', quantity: '3.125000', price: '96.0000', amount: '300.00', realizedGain: null, tradedAt: '#string' }, { execId: 'e2', quantity: '2.083333', price: '96.0000', amount: '200.00', realizedGain: null, tradedAt: '#string' }]

    Given path 'portfolios', portfolioId
    When method get
    Then match response.holdings == [{ isin: 'IE00B4L5Y983', quantity: '5.208333', reservedForSale: '0.000000', costBasis: '500.00', lastPrice: '96.0000' }]

    Given path 'portfolios', portfolioId, 'transactions'
    When method get
    Then match response.transactions[*].type == ['BUY', 'BUY', 'FUNDING']
    And match response.transactions[*].balanceAfter == ['0.00', '200.00', '500.00']
    And match response.transactions[0] contains { amount: '-200.00', isin: 'IE00B4L5Y983', quantity: '2.083333', price: '96.0000', orderId: '#(orderId)' }

  Scenario: every money booking is a balanced posting for Finance
    * def bought = call read('common.feature@buy') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: 'DE0007164600', amount: '100.00' }
    * hooks.brokerFilled(bought.orderId, 'x1', '0.5', '200.0000', true)
    * def funding = hooks.awaitPosting(portfolioId)
    * match funding contains { type: 'FUNDING', reference: '#(bought.fundingTransferId)', currency: 'EUR', valueDate: '#string' }
    * match funding.lines == [{ bookAccount: 'transfer-clearing', side: 'DEBIT', amount: '100.00' }, { bookAccount: '#("customer:" + portfolioId)', side: 'CREDIT', amount: '100.00' }]
    * def fill = hooks.awaitPosting(portfolioId)
    * match fill contains { type: 'BUY_FILL', reference: '#(bought.orderId + ":x1")' }
    * match fill.lines == [{ bookAccount: '#("customer:" + portfolioId)', side: 'DEBIT', amount: '100.00' }, { bookAccount: 'broker-settlement', side: 'CREDIT', amount: '100.00' }]

  Scenario: what a partly filled buy didn't spend is refunded to the current account when it expires
    * def bought = call read('common.feature@buy') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: 'IE00B5BMR087', amount: '1000.00' }
    * def orderId = bought.orderId
    * hooks.brokerFilled(orderId, 'p1', '1.5', '500.0000', false)
    Given path 'orders', orderId
    And retry until response.status == 'PARTIALLY_FILLED'
    When method get
    * hooks.brokerExpired(orderId)

    * def refund = hooks.awaitTransferRequest(portfolioId, currentAccount)
    * match refund contains { customerId: '#(portfolio.customerId)', amount: { amount: '250.00', currency: 'EUR' } }
    * match refund.transferId == '#regex inv-payout-[0-9a-f-]{36}-1'
    Given path 'orders', orderId
    When method get
    Then match response contains { status: 'EXPIRED', settlement: 'PENDING', filledAmount: '750.00' }

    * def answer = hooks.payOut(portfolioId, refund.transferId, '250.00')
    * match answer == { type: 'MONEY_WITHDRAWN', transferId: '#(refund.transferId)', amount: { amount: '250.00', currency: 'EUR' } }
    Given path 'orders', orderId
    And retry until response.settlement == 'SETTLED'
    When method get
    Given path 'portfolios', portfolioId, 'transactions'
    When method get
    Then match response.transactions[0] contains { type: 'PAYOUT', amount: '-250.00', balanceAfter: '0.00', reference: '#(refund.transferId)' }

  Scenario: a buy the broker rejects gets all its money back
    * def bought = call read('common.feature@buy') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: 'DE0005557508', amount: '50.00' }
    * hooks.brokerRejected(bought.orderId, 'market closed')
    * def refund = hooks.awaitTransferRequest(portfolioId, currentAccount)
    * match refund.amount.amount == '50.00'
    Given path 'orders', bought.orderId
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'market closed', settlement: 'PENDING', fills: [] }

  Scenario: if the current account refuses the debit, nothing is traded
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: 'IE00B4L5Y983', side: 'BUY', amount: '9000.00' }
    When method post
    * def orderId = response.orderId
    * def funding = hooks.awaitTransferRequest(currentAccount, portfolioId)
    * hooks.transferFailed(funding.transferId, 'DEBIT_REJECTED', 'overdraft limit exceeded')
    Given path 'orders', orderId
    And retry until response.status == 'FUNDING_FAILED'
    When method get
    Then match response contains { settlement: 'SETTLED', rejectionReason: 'DEBIT_REJECTED: overdraft limit exceeded' }

  Scenario Outline: a buy that can't be bought is rejected before any money moves - <case>
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(portfolioId)', isin: '<isin>', side: 'BUY', amount: '10.00' }
    When method post
    Then status 202
    * def orderId = response.orderId
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', settlement: 'SETTLED', rejectionReason: '<reason>' }

    Examples:
      | case               | isin         | reason                 |
      | off sale           | DE000BAY0017 | instrument not on sale |
      | unknown instrument | US0378331005 | unknown instrument     |

  Scenario: the same order again is safe; another order under the same key is refused
    * def key = hooks.uuid()
    * def body = { portfolioId: '#(portfolioId)', isin: 'IE00B4L5Y983', side: 'BUY', amount: '20.00' }
    Given path 'orders'
    And header Idempotency-Key = key
    And request body
    When method post
    * def orderId = response.orderId
    * def funding = hooks.awaitTransferRequest(currentAccount, portfolioId)
    Given path 'orders', orderId
    And retry until responseStatus == 200
    When method get

    Given path 'orders'
    And header Idempotency-Key = key
    And request body
    When method post
    Then status 202
    And match response contains { orderId: '#(orderId)', status: 'FUNDING' }
    * set body.amount = '20.01'
    Given path 'orders'
    And header Idempotency-Key = key
    And request body
    When method post
    Then status 422

  Scenario: a fill reported twice is applied once; a fill beyond the order's money is dead-lettered
    * def bought = call read('common.feature@buy') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: 'IE00BK5BQT80', amount: '100.00' }
    * def orderId = bought.orderId
    * hooks.brokerFilled(orderId, 'd1', '0.5', '100.0000', false)
    * hooks.brokerFilled(orderId, 'd1', '0.5', '100.0000', false)
    * hooks.brokerFilled(orderId, 'd2', '0.6', '100.0000', false)
    * def cause = hooks.awaitDeadLetter('broker.executions.v1', orderId)
    * match cause == 'com.example.investments.domain.IllegalExecutionException'
    Given path 'orders', orderId
    When method get
    Then match response contains { status: 'PARTIALLY_FILLED', filledAmount: '50.00', filledQuantity: '0.500000' }
    And match response.fills == '#[1]'

  Scenario: money nobody ordered goes straight back to the current account
    * def strayTransfer = 'tr-' + hooks.uuid()
    * hooks.deposit(portfolioId, strayTransfer, '42.00')
    * def deposited = hooks.awaitPortfolioEvent(portfolioId)
    * match deposited.type == 'MONEY_DEPOSITED'
    * def back = hooks.awaitTransferRequest(portfolioId, currentAccount)
    * match back.amount.amount == '42.00'

  Scenario: an instruction sent again is answered once
    * def bought = call read('common.feature@buy') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: 'IE00B4L5Y983', amount: '30.00' }
    * hooks.deposit(portfolioId, bought.fundingTransferId, '30.00')
    * hooks.deposit(portfolioId, bought.fundingTransferId, '31.00')
    * def cause = hooks.awaitDeadLetter('payments.investment-instructions.v1', portfolioId)
    * match cause == 'com.example.investments.application.IllegalInstructionException'
    Given path 'portfolios', portfolioId, 'transactions'
    When method get
    Then match response.transactions == '#[1]'

  Scenario Outline: invalid orders are refused before they are accepted - <case>
    * def body = { portfolioId: '#(portfolioId)', isin: 'IE00B4L5Y983', side: 'BUY', amount: '10.00' }
    * set body.<field> = <value>
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request body
    When method post
    Then status 400

    Examples:
      | case             | field    | value          |
      | below minimum    | amount   | '0.99'         |
      | three decimals   | amount   | '1.001'        |
      | no amount        | amount   | null           |
      | with a quantity  | quantity | '1'            |
      | not an ISIN      | isin     | 'APPLE'        |
      | no side          | side     | null           |

  Scenario: an order for a portfolio that isn't open is a 404 problem
    Given path 'orders'
    And header Idempotency-Key = hooks.uuid()
    And request { portfolioId: '#(hooks.uuid())', isin: 'IE00B4L5Y983', side: 'BUY', amount: '10.00' }
    When method post
    Then status 404
