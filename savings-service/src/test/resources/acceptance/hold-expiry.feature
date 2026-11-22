Feature: Holds expire, but a late confirmation is still booked

  Background:
    * hooks.setClock('2026-03-02T10:00:00Z')
    * def opened = call read('common.feature') { rate: 0.0 }
    * def accountId = opened.accountId
    * url baseUrl
    * hooks.deposit(accountId, '100.00')
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_DEPOSITED')

  Scenario: an unanswered hold is released after 72 hours
    * def requestId = hooks.requestWithdrawal(accountId, '100.00')
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')

    * hooks.setClock('2026-03-05T09:59:59Z')
    * hooks.runHoldExpiryJob()
    Given path 'accounts', accountId
    When method get
    Then match response.heldAmount == '100.00'

    * hooks.setClock('2026-03-05T10:00:00Z')
    * hooks.runHoldExpiryJob()
    * def released = hooks.awaitEvent(accountId, 'FUNDS_RELEASED')
    * match released == { requestId: '#(requestId)', amount: { amount: '100.00', currency: 'EUR' }, reason: 'EXPIRED', eventId: '#string' }
    Given path 'accounts', accountId
    When method get
    Then match response contains { balance: '100.00', heldAmount: '0.00', availableBalance: '100.00' }

  Scenario: money that already left the bank is booked even if it overdraws the account
    * def late = hooks.requestWithdrawal(accountId, '100.00')
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')
    * hooks.setClock('2026-03-06T10:00:00Z')
    * hooks.runHoldExpiryJob()
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RELEASED')

    # Funds were released, so the customer could withdraw them again,
    * def again = hooks.requestWithdrawal(accountId, '100.00')
    * def ignored = hooks.awaitEvent(accountId, 'FUNDS_RESERVED')
    * hooks.confirmWithdrawal(accountId, again)
    * def ignored = hooks.awaitEvent(accountId, 'MONEY_WITHDRAWN')

    # and then Payments reports the first transfer as executed after all.
    * hooks.confirmWithdrawal(accountId, late)
    * def withdrawn = hooks.awaitEvent(accountId, 'MONEY_WITHDRAWN')
    * match withdrawn.requestId == late
    * match withdrawn.balance.amount == '-100.00'
    Given path 'accounts', accountId
    When method get
    Then match response contains { balance: '-100.00', heldAmount: '0.00', availableBalance: '-100.00' }
