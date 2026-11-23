@ignore
Feature: Reusable steps

  # Opens an account through an order (ADR 0020) on a new test product with the given rate and,
  # optionally, payout frequency (MONTHLY by default), so a feature's arithmetic doesn't depend on the
  # products the bank sells. The order's funding transfer is left open: features move money themselves.
  Scenario: open an account
    * def productCode = hooks.product(rate, karate.get('payout', 'MONTHLY'))
    * def orderId = hooks.uuid()
    Given url baseUrl
    * def orderKey = orderId
    And path 'orders'
    And header Idempotency-Key = orderKey
    And request
      """
      {
        customerId: '#(hooks.uuid())', productCode: '#(productCode)', productVersion: 1, amount: '1.00',
        fromAccountId: '#(hooks.uuid())'
      }
      """
    When method post
    Then status 202
    * def orderId = response.orderId

    Given path 'orders', orderId
    And retry until responseStatus == 200 && response.status == 'FUNDING'
    When method get
    * def accountId = response.accountId

    Given path 'accounts', accountId
    When method get
    Then status 200
    * def account = response
