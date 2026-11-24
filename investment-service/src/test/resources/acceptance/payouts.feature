Feature: Payouts the current account refuses (ADR 0026)

  Background:
    * url baseUrl
    * def portfolio = call read('common.feature@open')
    * def portfolioId = portfolio.portfolioId
    * def currentAccount = portfolio.currentAccount
    # A buy of 100.00 that the broker rejects: all 100.00 is to be refunded.
    * def bought = call read('common.feature@buy') { portfolioId: '#(portfolioId)', currentAccount: '#(currentAccount)', isin: 'IE00B4L5Y983', amount: '100.00' }
    * def orderId = bought.orderId
    * hooks.brokerRejected(orderId, 'instrument halted')
    * def refund = hooks.awaitTransferRequest(portfolioId, currentAccount)

  Scenario: a refund the blocked current account refuses comes back, is shown unpaid, and is paid once the account is unblocked
    * hooks.withdraw(portfolioId, refund.transferId, '100.00')
    * def withdrawn = hooks.awaitPortfolioEvent(portfolioId)
    * match withdrawn.type == 'MONEY_WITHDRAWN'
    * hooks.returnTransfer(portfolioId, refund.transferId)
    * def returned = hooks.awaitPortfolioEvent(portfolioId)
    * match returned == { type: 'WITHDRAWAL_RETURNED', transferId: '#(refund.transferId)', amount: { amount: '100.00', currency: 'EUR' } }
    * hooks.transferFailed(refund.transferId, 'CREDIT_REFUSED', 'account blocked')

    Given path 'orders', orderId
    And retry until response.settlement == 'UNPAID'
    When method get
    Given path 'portfolios', portfolioId
    When method get
    Then match response.unpaidPayouts == [{ payoutId: '#uuid', orderId: '#(orderId)', reason: 'REFUND', amount: '100.00', unpaidSince: '#string', attempts: 1 }]
    Given path 'portfolios', portfolioId, 'transactions'
    When method get
    Then match response.transactions[0] contains { type: 'PAYOUT_RETURN', amount: '100.00', balanceAfter: '100.00' }

    * hooks.currentAccountUnblocked(currentAccount)
    * def retry = hooks.awaitTransferRequest(portfolioId, currentAccount)
    * match retry.transferId == refund.transferId.replace(/-1$/, '-2')
    * match retry.amount.amount == '100.00'
    Given path 'orders', orderId
    And retry until response.settlement == 'PENDING'
    When method get
    * def answer = hooks.payOut(portfolioId, retry.transferId, '100.00')
    * match answer.type == 'MONEY_WITHDRAWN'
    Given path 'orders', orderId
    And retry until response.settlement == 'SETTLED'
    When method get
    Given path 'portfolios', portfolioId
    When method get
    Then match response.unpaidPayouts == []

  Scenario: an unpaid payout is retried by the daily job and counted once it has waited too long
    * hooks.transferFailed(refund.transferId, 'INVALID_REQUEST', 'account 1 does not belong to customer 2')
    Given path 'orders', orderId
    And retry until response.settlement == 'UNPAID'
    When method get
    * def before = hooks.gauge('investments.payouts.unpaid.overdue')
    * hooks.setClock('2099-01-01T05:00:00Z')
    * assert hooks.gauge('investments.payouts.unpaid.overdue') >= before + 1
    * hooks.runUnpaidPayoutJob()
    * def retry = hooks.awaitTransferRequest(portfolioId, currentAccount)
    * match retry.transferId == refund.transferId.replace(/-1$/, '-2')
    * hooks.resetClock()

  Scenario: money leaves a portfolio only under the transfer id of a payout investments asked for
    * def stranger = 'tr-' + hooks.uuid()
    * hooks.withdraw(portfolioId, stranger, '100.00')
    * def answer = hooks.awaitPortfolioEvent(portfolioId)
    * match answer == { type: 'WITHDRAWAL_REJECTED', transferId: '#(stranger)', amount: { amount: '100.00', currency: 'EUR' }, reason: 'nothing owed under this transfer id' }
    * hooks.withdraw(portfolioId, refund.transferId, '99.00')
    * def wrongAmount = hooks.awaitPortfolioEvent(portfolioId)
    * match wrongAmount.type == 'WITHDRAWAL_REJECTED'

  Scenario: a return of money that never left is dead-lettered
    * def never = 'tr-' + hooks.uuid()
    * hooks.returnTransfer(portfolioId, never)
    * def cause = hooks.awaitDeadLetter('payments.investment-instructions.v1', portfolioId)
    * match cause == 'com.example.investments.application.IllegalInstructionException'
