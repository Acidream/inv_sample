Feature: Answering the transfer service's instructions (ADR 0018, 0021)

  Background:
    * hooks.resetClock()
    * url baseUrl
    * def transferId = 'tr-' + hooks.uuid()

  Scenario: a debit within the balance is carried out and booked
    * def account = call read('common.feature') { overdraftLimit: '0.00', funds: '300.00' }
    * def accountId = account.accountId
    * hooks.setClock('2026-11-23T09:15:00Z')
    * hooks.debit(accountId, transferId, '120.00')

    * def answer = hooks.awaitEvent(accountId)
    * match answer == { type: 'CURRENT_ACCOUNT_DEBITED', transferId: '#(transferId)', amount: { amount: '120.00', currency: 'EUR' }, occurredAt: '2026-11-23T09:15:00Z' }
    Given path 'current-accounts', accountId
    When method get
    Then match response contains { balance: '180.00', available: '180.00' }
    Given path 'current-accounts', accountId, 'bookings'
    When method get
    # Dated when the transfer service sent the instruction, not when it was processed (ADR 0019).
    Then match response[0] == { reference: '#(transferId)', type: 'TRANSFER_DEBIT', amount: '-120.00', balanceAfter: '180.00', bookedAt: '2026-11-23T09:15:00Z' }

  Scenario: a debit may use the overdraft, down to its limit
    * def account = call read('common.feature') { overdraftLimit: '500.00', funds: '100.00' }
    * def accountId = account.accountId
    * hooks.debit(accountId, transferId, '600.00')
    * def answer = hooks.awaitEvent(accountId)
    * match answer.type == 'CURRENT_ACCOUNT_DEBITED'
    Given path 'current-accounts', accountId
    When method get
    Then match response contains { balance: '-500.00', available: '0.00' }

  Scenario: a debit beyond the overdraft limit is rejected and nothing is booked
    * def account = call read('common.feature') { overdraftLimit: '500.00', funds: '100.00' }
    * def accountId = account.accountId
    * hooks.debit(accountId, transferId, '600.01')
    * def answer = hooks.awaitEvent(accountId)
    * match answer == { type: 'DEBIT_REJECTED', transferId: '#(transferId)', reason: 'overdraft limit exceeded', occurredAt: '#string' }
    Given path 'current-accounts', accountId
    When method get
    Then match response.balance == '100.00'
    Given path 'current-accounts', accountId, 'bookings'
    When method get
    Then match response == '#[1]'

  Scenario: a credit is carried out and booked
    * def account = call read('common.feature') { overdraftLimit: '0.00' }
    * def accountId = account.accountId
    * hooks.credit(accountId, transferId, '40.00')
    * def answer = hooks.awaitEvent(accountId)
    * match answer contains { type: 'CURRENT_ACCOUNT_CREDITED', transferId: '#(transferId)', amount: { amount: '40.00', currency: 'EUR' } }
    Given path 'current-accounts', accountId
    When method get
    Then match response.balance == '40.00'

  Scenario: a blocked account rejects debits and credits; the transfer service then gives the money back
    * def account = call read('common.feature') { overdraftLimit: '0.00', funds: '100.00' }
    * def accountId = account.accountId
    Given path 'current-accounts', accountId, 'block'
    And request { reason: 'deceased' }
    When method put
    Then status 202
    Given path 'current-accounts', accountId
    And retry until response.status == 'BLOCKED'
    When method get

    * hooks.credit(accountId, transferId, '40.00')
    * def answer = hooks.awaitEvent(accountId)
    * match answer contains { type: 'CREDIT_REJECTED', transferId: '#(transferId)', reason: 'account blocked' }
    * def debitId = 'tr-' + hooks.uuid()
    * hooks.debit(accountId, debitId, '10.00')
    * def answer = hooks.awaitEvent(accountId)
    * match answer contains { type: 'DEBIT_REJECTED', transferId: '#(debitId)', reason: 'account blocked' }
    Given path 'current-accounts', accountId
    When method get
    Then match response.balance == '100.00'

  Scenario: an instruction for an unknown account is rejected
    * def accountId = hooks.uuid()
    * hooks.debit(accountId, transferId, '10.00')
    * def answer = hooks.awaitEvent(accountId)
    * match answer contains { type: 'DEBIT_REJECTED', reason: 'unknown account' }

  Scenario: a redelivered instruction, or the same transfer sent again, is answered and booked once
    * def account = call read('common.feature') { overdraftLimit: '0.00', funds: '100.00' }
    * def accountId = account.accountId
    * def eventId = hooks.debit(accountId, transferId, '30.00')
    * def answer = hooks.awaitEvent(accountId)
    * match answer.type == 'CURRENT_ACCOUNT_DEBITED'

    * hooks.redeliverDebit(eventId, accountId, transferId, '30.00')
    * hooks.debit(accountId, transferId, '30.00')
    # Instructions for one account are consumed in order: if either duplicate had been answered, its
    # answer would arrive before this one.
    * def laterId = 'tr-' + hooks.uuid()
    * hooks.credit(accountId, laterId, '1.00')
    * def answer = hooks.awaitEvent(accountId)
    * match answer contains { type: 'CURRENT_ACCOUNT_CREDITED', transferId: '#(laterId)' }

    Given path 'current-accounts', accountId
    When method get
    Then match response.balance == '71.00'

  Scenario: a transfer id reused for a different amount is a contract violation and dead-lettered
    * def account = call read('common.feature') { overdraftLimit: '0.00', funds: '100.00' }
    * def accountId = account.accountId
    * hooks.debit(accountId, transferId, '30.00')
    * def answer = hooks.awaitEvent(accountId)
    * hooks.debit(accountId, transferId, '31.00')
    * def cause = hooks.awaitDeadLetter('payments.current-account-instructions.v1', accountId)
    * match cause == 'java.lang.IllegalArgumentException'
    Given path 'current-accounts', accountId
    When method get
    Then match response.balance == '70.00'

  Scenario: an unreadable instruction is dead-lettered
    * def accountId = hooks.uuid()
    * hooks.sendGarbageInstruction(accountId)
    * def cause = hooks.awaitDeadLetter('payments.current-account-instructions.v1', accountId)
    * match cause contains 'com.google.protobuf.InvalidProtocolBufferException'
