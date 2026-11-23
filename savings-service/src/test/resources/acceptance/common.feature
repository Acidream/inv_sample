@ignore
Feature: Reusable steps

  # Opens an account on a new test product with the given rate and, optionally, payout frequency
  # (MONTHLY by default), so a feature's arithmetic doesn't depend on the products the bank sells.
  Scenario: open an account
    * def productCode = hooks.product(rate, karate.get('payout', 'MONTHLY'))
    Given url baseUrl
    And path 'accounts'
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)' }
    When method post
    Then status 201
    * def account = response
    * def accountId = response.id
