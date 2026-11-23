Feature: Transfers between a customer's current account and savings (ADR 0015, 0018)

  Background:
    * hooks.resetClock()
    * url baseUrl
    * def customerId = hooks.uuid()
    * def currentAccount = hooks.currentAccountOpened(customerId)
    * def savingsAccount = hooks.savingsAccountOpened(customerId)
    * def transferId = 'tr-' + hooks.uuid()

  Scenario: money from the current account to savings: debit the current account, then credit savings
    Given path 'transfers', transferId
    And request { customerId: '#(customerId)', fromAccountId: '#(currentAccount)', toAccountId: '#(savingsAccount)', amount: '100.00' }
    When method put
    Then status 202
    And match responseHeaders['Location'][0] == '/transfers/' + transferId
    And match response contains { transferId: '#(transferId)', status: 'RECEIVED' }

    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * match debit == { transferId: '#(transferId)', amount: { amount: '100.00', currency: 'EUR' } }
    * hooks.currentAccountDebited(currentAccount, transferId, '100.00')

    * def credit = hooks.awaitSavingsInstruction(savingsAccount, 'TRANSFER_FROM_CURRENT_ACCOUNT')
    * match credit == { transferId: '#(transferId)', amount: { amount: '100.00', currency: 'EUR' } }
    * hooks.savingsDeposited(savingsAccount, transferId, '100.00')

    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome.type == 'TRANSFER_COMPLETED'
    Given path 'transfers', transferId
    When method get
    Then match response contains { status: 'COMPLETED', route: 'CURRENT_TO_SAVINGS', failureReason: null }

  Scenario: the current account refuses the debit: nothing moves
    * hooks.requestTransfer(transferId, customerId, currentAccount, savingsAccount, '5000.00')
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * hooks.currentAccountDebitRejected(currentAccount, transferId, 'overdraft limit exceeded')

    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome == { reason: 'DEBIT_REJECTED', detail: 'overdraft limit exceeded', type: 'TRANSFER_FAILED' }
    Given path 'transfers', transferId
    When method get
    Then match response contains { status: 'FAILED', failureReason: 'DEBIT_REJECTED' }

  Scenario: money from savings to the current account: savings debits at once, then the current account is credited
    * hooks.requestTransfer(transferId, customerId, savingsAccount, currentAccount, '40.00')
    * def debit = hooks.awaitSavingsInstruction(savingsAccount, 'TRANSFER_TO_CURRENT_ACCOUNT')
    * match debit.transferId == transferId
    * hooks.savingsWithdrawn(savingsAccount, transferId, '40.00')

    * def credit = hooks.awaitCurrentAccountInstruction(currentAccount, 'CREDIT_CURRENT_ACCOUNT')
    * match credit == { transferId: '#(transferId)', amount: { amount: '40.00', currency: 'EUR' } }
    * hooks.currentAccountCredited(currentAccount, transferId, '40.00')

    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome.type == 'TRANSFER_COMPLETED'

  Scenario: savings refuses the withdrawal
    * hooks.requestTransfer(transferId, customerId, savingsAccount, currentAccount, '40.00')
    * def debit = hooks.awaitSavingsInstruction(savingsAccount, 'TRANSFER_TO_CURRENT_ACCOUNT')
    * hooks.savingsWithdrawalRejected(savingsAccount, transferId, '40.00')
    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome == { reason: 'DEBIT_REJECTED', detail: 'INSUFFICIENT_FUNDS', type: 'TRANSFER_FAILED' }

  Scenario: the current account refuses the credit: savings gets the money back
    * hooks.requestTransfer(transferId, customerId, savingsAccount, currentAccount, '40.00')
    * def debit = hooks.awaitSavingsInstruction(savingsAccount, 'TRANSFER_TO_CURRENT_ACCOUNT')
    * hooks.savingsWithdrawn(savingsAccount, transferId, '40.00')
    * def credit = hooks.awaitCurrentAccountInstruction(currentAccount, 'CREDIT_CURRENT_ACCOUNT')
    * hooks.currentAccountCreditRejected(currentAccount, transferId, 'account closed')

    * def returned = hooks.awaitSavingsInstruction(savingsAccount, 'TRANSFER_RETURNED')
    * match returned == { transferId: '#(transferId)' }
    Given path 'transfers', transferId
    When method get
    Then match response.status == 'RETURNING'
    * hooks.savingsWithdrawalReturned(savingsAccount, transferId, '40.00')

    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome == { reason: 'CREDIT_REFUSED', detail: 'account closed', type: 'TRANSFER_FAILED' }

  Scenario: a request for an account that was just opened waits until the account is known
    * def newSavings = hooks.uuid()
    * hooks.requestTransfer(transferId, customerId, currentAccount, newSavings, '10.00')
    Given path 'transfers', transferId
    And retry until responseStatus == 200
    When method get
    Then match response.status == 'AWAITING_ACCOUNTS'

    * hooks.savingsAccountOpenedWithId(customerId, newSavings)
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * match debit.transferId == transferId

  Scenario: a request whose accounts never become known fails after a while
    * hooks.setClock('2026-03-01T10:00:00Z')
    * hooks.requestTransfer(transferId, customerId, currentAccount, hooks.uuid(), '10.00')
    Given path 'transfers', transferId
    And retry until responseStatus == 200
    When method get
    * hooks.setClock('2026-03-01T10:11:00Z')
    * hooks.runAwaitingAccountsJob()
    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome == { reason: 'INVALID_REQUEST', detail: 'accounts not known in time', type: 'TRANSFER_FAILED' }

  Scenario: money can't move to another customer's account
    * def strangersSavings = hooks.savingsAccountOpened(hooks.uuid())
    * hooks.requestTransfer(transferId, customerId, currentAccount, strangersSavings, '10.00')
    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome.reason == 'INVALID_REQUEST'
    * match outcome.detail contains 'does not belong to customer'

  Scenario: only transfers between a current account and savings are supported
    * def otherSavings = hooks.savingsAccountOpened(customerId)
    * hooks.requestTransfer(transferId, customerId, savingsAccount, otherSavings, '10.00')
    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome == { reason: 'INVALID_REQUEST', detail: 'transfers from SAVINGS to SAVINGS are not supported', type: 'TRANSFER_FAILED' }

  Scenario: requesting the same transfer again is safe; a different transfer under the same id is refused
    * def body = { customerId: '#(customerId)', fromAccountId: '#(currentAccount)', toAccountId: '#(savingsAccount)', amount: '100.00' }
    Given path 'transfers', transferId
    And request body
    When method put
    Then status 202
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')

    Given path 'transfers', transferId
    And request body
    When method put
    Then status 202
    And match response.status == 'DEBITING'

    * set body.amount = '100.01'
    Given path 'transfers', transferId
    And request body
    When method put
    Then status 422

  Scenario: a request that reuses a transfer id for a different transfer is dead-lettered
    * hooks.requestTransfer(transferId, customerId, currentAccount, savingsAccount, '10.00')
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * hooks.requestTransfer(transferId, customerId, currentAccount, savingsAccount, '20.00')
    * def cause = hooks.awaitDeadLetter('payments.transfer-requests.v1', transferId)
    * match cause == 'java.lang.IllegalArgumentException'

  Scenario Outline: invalid requests are refused before they are accepted - <case>
    * def body = { customerId: '#(customerId)', fromAccountId: '#(currentAccount)', toAccountId: '#(savingsAccount)', amount: '1.00' }
    * set body.<field> = <value>
    Given path 'transfers', transferId
    And request body
    When method put
    Then status 400

    Examples:
      | case           | field         | value   |
      | no customer    | customerId    | null    |
      | no source      | fromAccountId | null    |
      | no target      | toAccountId   | null    |
      | zero amount    | amount        | '0'     |
      | three decimals | amount        | '1.001' |

  Scenario: an unknown transfer is a 404 problem
    Given path 'transfers', 'tr-unknown'
    When method get
    Then status 404
