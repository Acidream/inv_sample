Feature: Interest accrues daily (ACT/365) and is paid out per the product's payout frequency

  Background:
    * url baseUrl

  Scenario: a quarterly product pays a quarter's interest once, after the quarter ends
    # 1000.00 at 3.65 % p.a. earns exactly 0.10 per day; Q1 2026 has 31 + 28 + 31 = 90 days
    * hooks.setClock('2026-01-01T09:00:00Z')
    * def opened = call read('common.feature') { rate: 0.0365, payout: 'QUARTERLY' }
    * def accountId = opened.accountId
    * hooks.deposit(accountId, '1000.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')

    # After January: accrued, not paid
    * hooks.setClock('2026-01-31T23:15:00Z')
    * hooks.runInterestJob()
    Given path 'accounts', accountId
    When method get
    Then match response contains { balance: '1000.00', accruedInterest: '3.1000000000' }

    # 00:15 Berlin time (summer time) on 1 April
    * hooks.setClock('2026-03-31T22:15:00Z')
    * hooks.runInterestJob()
    * def paid = hooks.awaitEvent(accountId, 'INTEREST_PAID')
    * match paid contains { period: '2026-Q1', periodStart: '2026-01-01', periodEnd: '2026-03-31' }
    * match paid.amount.amount == '9.00'
    * match paid.balance.amount == '1009.00'

  Scenario: a full month of interest is credited on the first of the next month
    # 1000.00 at 3.65 % p.a. earns exactly 0.10 per day
    * hooks.setClock('2026-01-01T09:00:00Z')
    * def opened = call read('common.feature') { rate: 0.0365 }
    * def accountId = opened.accountId
    * hooks.deposit(accountId, '1000.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')

    # 00:15 Berlin time on 1 February, when the nightly job runs in production
    * hooks.setClock('2026-01-31T23:15:00Z')
    * hooks.runInterestJob()
    * def paid = hooks.awaitEvent(accountId, 'INTEREST_PAID')
    * match paid contains { period: '2026-01', periodStart: '2026-01-01', periodEnd: '2026-01-31' }
    * match paid.amount.amount == '3.10'
    * match paid.balance.amount == '1003.10'

    Given path 'accounts', accountId
    When method get
    Then match response contains { balance: '1003.10', accruedInterest: '0.0000000000' }

    # Running the job again for the same day changes nothing
    * hooks.runInterestJob()
    Given path 'accounts', accountId
    When method get
    Then match response.balance == '1003.10'

  Scenario: catching up after missed nights uses each day's closing balance
    # 365.00 at 10 % earns exactly 0.10 per day; the deposit on 2026-01-11 doubles that.
    * hooks.setClock('2026-01-01T09:00:00Z')
    * def opened = call read('common.feature') { rate: 0.1 }
    * def accountId = opened.accountId
    * hooks.deposit(accountId, '365.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * hooks.setClock('2026-01-11T12:00:00Z')
    * hooks.deposit(accountId, '365.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')

    # The job did not run for the whole month; it catches up on 1 February.
    * hooks.setClock('2026-02-01T06:00:00Z')
    * hooks.runInterestJob()
    * def paid = hooks.awaitEvent(accountId, 'INTEREST_PAID')
    # 10 days x 0.10 + 21 days x 0.20 = 5.20
    * match paid.amount.amount == '5.20'

  Scenario: sub-cent remainders are carried over, not lost
    # 100.00 at 1 % earns 0.0027397260 per day = 0.0849315060 in January: 0.08 paid, the rest carried
    * hooks.setClock('2026-01-01T09:00:00Z')
    * def opened = call read('common.feature') { rate: 0.01 }
    * def accountId = opened.accountId
    * hooks.deposit(accountId, '100.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')
    * hooks.setClock('2026-02-01T06:00:00Z')
    * hooks.runInterestJob()
    * def paid = hooks.awaitEvent(accountId, 'INTEREST_PAID')
    * match paid.amount.amount == '0.08'
    Given path 'accounts', accountId
    When method get
    Then match response.accruedInterest == '0.0049315060'
