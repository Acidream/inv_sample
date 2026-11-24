Feature: Transfers between a customer's current account and savings or investments (ADR 0015, 0018, 0028)

  Background:
    * hooks.resetClock()
    * url baseUrl
    * def customerId = hooks.uuid()
    * def currentAccount = hooks.currentAccountOpened(customerId)
    * def savingsAccount = hooks.savingsAccountOpened(customerId)
    * def transferId = 'tr-' + hooks.uuid()

  Scenario: money from the current account to savings: debit the current account, then credit savings
    * def transferKey = transferId
    Given path 'transfers'
    And header Idempotency-Key = transferKey
    And request { customerId: '#(customerId)', fromAccountId: '#(currentAccount)', toAccountId: '#(savingsAccount)', amount: '100.00' }
    When method post
    Then status 202
    * def transferId = response.transferId
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

  Scenario: money for a buy order: the current account is debited, then the portfolio credited
    * def portfolio = hooks.portfolioOpened(customerId)
    * hooks.requestTransfer(transferId, customerId, currentAccount, portfolio, '500.00')
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * hooks.currentAccountDebited(currentAccount, transferId, '500.00')

    * def credit = hooks.awaitInvestmentInstruction(portfolio, 'TRANSFER_FROM_CURRENT_ACCOUNT')
    * match credit == { transferId: '#(transferId)', amount: { amount: '500.00', currency: 'EUR' } }
    * hooks.investmentsDeposited(portfolio, transferId, '500.00')

    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome.type == 'TRANSFER_COMPLETED'
    Given path 'transfers', transferId
    When method get
    Then match response contains { status: 'COMPLETED', route: 'CURRENT_TO_INVESTMENTS' }

  Scenario: a refund the current account refuses goes back to the portfolio
    * def portfolio = hooks.portfolioOpened(customerId)
    * hooks.requestTransfer(transferId, customerId, portfolio, currentAccount, '12.34')
    * def debit = hooks.awaitInvestmentInstruction(portfolio, 'TRANSFER_TO_CURRENT_ACCOUNT')
    * hooks.investmentsWithdrawn(portfolio, transferId, '12.34')
    * def credit = hooks.awaitCurrentAccountInstruction(currentAccount, 'CREDIT_CURRENT_ACCOUNT')
    * hooks.currentAccountCreditRejected(currentAccount, transferId, 'account blocked')

    * def returned = hooks.awaitInvestmentInstruction(portfolio, 'TRANSFER_RETURNED')
    * match returned == { transferId: '#(transferId)' }
    * hooks.investmentsWithdrawalReturned(portfolio, transferId, '12.34')
    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome == { reason: 'CREDIT_REFUSED', detail: 'account blocked', type: 'TRANSFER_FAILED' }

  Scenario: money doesn't move between savings and a portfolio directly
    * def portfolio = hooks.portfolioOpened(customerId)
    * hooks.requestTransfer(transferId, customerId, savingsAccount, portfolio, '10.00')
    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome == { reason: 'INVALID_REQUEST', detail: 'transfers from SAVINGS to INVESTMENTS are not supported', type: 'TRANSFER_FAILED' }

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

  Scenario: a request delivered late still gets the full time for its accounts to become known
    # Sent an hour ago, held up in a backlog: the timeout counts from when the transfer service got it.
    * hooks.setClock('2026-03-02T10:00:00Z')
    * def newSavings = hooks.uuid()
    * hooks.requestTransferSentAt(transferId, customerId, currentAccount, newSavings, '10.00', '2026-03-02T09:00:00Z')
    Given path 'transfers', transferId
    And retry until responseStatus == 200
    When method get
    * hooks.setClock('2026-03-02T10:05:00Z')
    * hooks.runAwaitingAccountsJob()
    Given path 'transfers', transferId
    When method get
    Then match response.status == 'AWAITING_ACCOUNTS'

    * hooks.savingsAccountOpenedWithId(customerId, newSavings)
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * match debit.transferId == transferId

  Scenario: a transfer that waits too long for a ledger's answer is counted as stuck until the answer comes
    * hooks.setClock('2026-04-01T10:00:00Z')
    * hooks.requestTransfer(transferId, customerId, currentAccount, savingsAccount, '10.00')
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * def before = hooks.stuckTransfers()

    * hooks.setClock('2026-04-01T10:16:00Z')
    * match hooks.stuckTransfers() == before + 1

    * hooks.currentAccountDebited(currentAccount, transferId, '10.00')
    * def credit = hooks.awaitSavingsInstruction(savingsAccount, 'TRANSFER_FROM_CURRENT_ACCOUNT')
    * match hooks.stuckTransfers() == before

  Scenario: an answer for another account under the transfer's id is dead-lettered and moves nothing
    * hooks.requestTransfer(transferId, customerId, currentAccount, savingsAccount, '10.00')
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * def otherAccount = hooks.currentAccountOpened(customerId)
    * hooks.currentAccountDebited(otherAccount, transferId, '10.00')
    * def cause = hooks.awaitDeadLetter('current-account.events.v1', otherAccount)
    * match cause == 'com.example.transfers.domain.IllegalTransferStateException'
    Given path 'transfers', transferId
    When method get
    Then match response.status == 'DEBITING'

  Scenario: an account id announced again by another ledger is dead-lettered, not ignored
    * hooks.savingsAccountOpenedWithId(customerId, currentAccount)
    * def cause = hooks.awaitDeadLetter('savings.account-events.v1', currentAccount)
    * match cause == 'com.example.transfers.application.AccountConflictException'

  Scenario: money can't move to another customer's account
    * def strangersSavings = hooks.savingsAccountOpened(hooks.uuid())
    * hooks.requestTransfer(transferId, customerId, currentAccount, strangersSavings, '10.00')
    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome.reason == 'INVALID_REQUEST'
    * match outcome.detail contains 'does not belong to customer'

  Scenario: only transfers with a current account on one side are supported
    * def otherSavings = hooks.savingsAccountOpened(customerId)
    * hooks.requestTransfer(transferId, customerId, savingsAccount, otherSavings, '10.00')
    * def outcome = hooks.awaitTransferEvent(transferId)
    * match outcome == { reason: 'INVALID_REQUEST', detail: 'transfers from SAVINGS to SAVINGS are not supported', type: 'TRANSFER_FAILED' }

  Scenario: requesting the same transfer again is safe; a different transfer under the same id is refused
    * def body = { customerId: '#(customerId)', fromAccountId: '#(currentAccount)', toAccountId: '#(savingsAccount)', amount: '100.00' }
    * def transferKey = transferId
    Given path 'transfers'
    And header Idempotency-Key = transferKey
    And request body
    When method post
    Then status 202
    * def transferId = response.transferId
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')

    Given path 'transfers'
    And header Idempotency-Key = transferKey
    And request body
    When method post
    Then status 202
    * def transferId = response.transferId
    And match response.status == 'DEBITING'

    * set body.amount = '100.01'
    Given path 'transfers'
    And header Idempotency-Key = transferKey
    And request body
    When method post
    Then status 422

  Scenario: a request that reuses a transfer id for a different transfer is dead-lettered
    * hooks.requestTransfer(transferId, customerId, currentAccount, savingsAccount, '10.00')
    * def debit = hooks.awaitCurrentAccountInstruction(currentAccount, 'DEBIT_CURRENT_ACCOUNT')
    * hooks.requestTransfer(transferId, customerId, currentAccount, savingsAccount, '20.00')
    * def cause = hooks.awaitDeadLetter('payments.transfer-requests.v1', transferId)
    * match cause == 'com.example.transfers.application.TransferIdInUseException'

  Scenario Outline: invalid requests are refused before they are accepted - <case>
    * def body = { customerId: '#(customerId)', fromAccountId: '#(currentAccount)', toAccountId: '#(savingsAccount)', amount: '1.00' }
    * set body.<field> = <value>
    * def transferKey = transferId
    Given path 'transfers'
    And header Idempotency-Key = transferKey
    And request body
    When method post
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

  Scenario: a client never chooses the transfer id, so savings' order ids can't be taken through the API
    Given path 'transfers'
    And header Idempotency-Key = 'savings-order-' + hooks.uuid()
    And request { customerId: '#(customerId)', fromAccountId: '#(currentAccount)', toAccountId: '#(savingsAccount)', amount: '1.00' }
    When method post
    Then status 202
    And match response.transferId == '#regex tr-[0-9a-f-]{36}'

  Scenario: a request without an idempotency key is refused, so a retry can never move money twice
    Given path 'transfers'
    And request { customerId: '#(customerId)', fromAccountId: '#(currentAccount)', toAccountId: '#(savingsAccount)', amount: '1.00' }
    When method post
    Then status 400

  Scenario: a malformed transfer id in the path is a client error
    Given path 'transfers', 'tr!1'
    When method get
    Then status 400
