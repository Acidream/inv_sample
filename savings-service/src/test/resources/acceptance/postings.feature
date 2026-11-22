Feature: Every booking reaches Finance as a balanced double-entry posting

  # Savings publishes postings on savings.postings.v1 against logical book accounts; Finance maps them
  # to its chart of accounts. The hooks check that every posting balances before a scenario sees it.

  Background:
    * url baseUrl

  Scenario: a deposit moves money from payments clearing to the customer
    * hooks.resetClock()
    * def opened = call read('common.feature') { rate: 0.02 }
    * def accountId = opened.accountId
    * def paymentId = 'pay-' + hooks.uuid()
    * hooks.depositPayment(accountId, paymentId, '100.00')
    * def posting = hooks.awaitPosting(accountId, 'DEPOSIT')
    * match posting contains { reference: '#(paymentId)', currency: 'EUR', postingId: '#uuid' }
    * match posting.lines ==
      """
      [
        { bookAccount: 'payments-clearing',     side: 'DEBIT',  amount: '100.00' },
        { bookAccount: '#("customer:" + accountId)', side: 'CREDIT', amount: '100.00' }
      ]
      """

  Scenario: holds are not bookings; a settled withdrawal moves money from the customer to clearing
    * hooks.resetClock()
    * def opened = call read('common.feature') { rate: 0.02 }
    * def accountId = opened.accountId
    * hooks.deposit(accountId, '100.00')
    * def ignored = hooks.awaitPosting(accountId, 'DEPOSIT')
    # Reserved and cancelled: no posting at all
    * def cancelledId = hooks.requestWithdrawal(accountId, '30.00')
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')
    * hooks.cancelWithdrawal(accountId, cancelledId)
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RELEASED')
    # Reserved and confirmed: one posting, when the money actually leaves
    * def requestId = hooks.requestWithdrawal(accountId, '40.00')
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')
    * hooks.confirmWithdrawal(accountId, requestId)
    * def posting = hooks.awaitPosting(accountId, 'WITHDRAWAL')
    * match posting.reference == requestId
    * match posting.lines ==
      """
      [
        { bookAccount: '#("customer:" + accountId)', side: 'DEBIT',  amount: '40.00' },
        { bookAccount: 'payments-clearing',     side: 'CREDIT', amount: '40.00' }
      ]
      """

  Scenario: interest is an expense accrued daily into a payable, then paid from it to the customer
    # 1000.00 at 3.65 % p.a. earns exactly 0.10 per day
    * hooks.setClock('2026-01-01T09:00:00Z')
    * def opened = call read('common.feature') { rate: 0.0365 }
    * def accountId = opened.accountId
    * hooks.deposit(accountId, '1000.00')
    * def ignored = hooks.awaitPosting(accountId, 'DEPOSIT')

    # The job runs at 00:15 Berlin time on 1 February
    * hooks.setClock('2026-01-31T23:15:00Z')
    * hooks.runInterestJob()

    # One accrual per day, each value-dated to the day it was earned, although computed in February
    * def accruals = hooks.awaitPostings(accountId, 'INTEREST_ACCRUAL', 31)
    * match each accruals[*].lines ==
      """
      [
        { bookAccount: 'interest-expense', side: 'DEBIT',  amount: '0.1000000000' },
        { bookAccount: 'interest-payable', side: 'CREDIT', amount: '0.1000000000' }
      ]
      """
    * match accruals[0] contains { valueDate: '2026-01-01', reference: 'accrual:2026-01-01' }
    * match accruals[30] contains { valueDate: '2026-01-31', reference: 'accrual:2026-01-31' }

    # The payout empties the payable into the customer's account
    * def payout = hooks.awaitPosting(accountId, 'INTEREST_PAYOUT')
    * match payout contains { reference: 'interest:2026-01', valueDate: '2026-02-01' }
    * match payout.lines ==
      """
      [
        { bookAccount: 'interest-payable', side: 'DEBIT',  amount: '3.10' },
        { bookAccount: '#("customer:" + accountId)', side: 'CREDIT', amount: '3.10' }
      ]
      """
