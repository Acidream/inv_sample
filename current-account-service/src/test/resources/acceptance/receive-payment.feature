@ignore
Feature: Reusable step: receive a payment of `amount` for `accountId` and wait until it is booked

  Scenario: receive a payment and wait until it is booked
    * def paymentId = 'pay-' + hooks.uuid()
    Given url baseUrl
    And path 'current-accounts', accountId, 'incoming-payments', paymentId
    And request { amount: '#(amount)' }
    When method put
    Then status 202
    Given path 'current-accounts', accountId, 'incoming-payments', paymentId
    And retry until responseStatus == 200 && response.status == 'BOOKED'
    When method get
