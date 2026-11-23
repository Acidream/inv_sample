@ignore
Feature: Reusable steps

  # Opens an account with the given overdraft limit and, if given, books an incoming payment of
  # `funds`, so the feature starts from a known balance. The opening event is consumed here.
  Scenario: open an account
    * def accountId = hooks.uuid()
    * def customerId = hooks.uuid()
    Given url baseUrl
    And path 'current-accounts', accountId
    And request { customerId: '#(customerId)', overdraftLimit: '#(overdraftLimit)' }
    When method put
    Then status 202

    Given path 'current-accounts', accountId
    And retry until responseStatus == 200
    When method get
    * def opened = hooks.awaitEvent(accountId)
    * match opened.type == 'CURRENT_ACCOUNT_OPENED'

    * def funds = karate.get('funds', null)
    * if (funds) karate.call('receive-payment.feature', { accountId: accountId, amount: funds })
