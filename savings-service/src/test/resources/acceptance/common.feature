@ignore
Feature: Reusable steps

  Scenario: open an account
    Given url baseUrl
    And path 'accounts'
    And request { customerId: '#(hooks.uuid())', annualRate: '#(rate)' }
    When method post
    Then status 201
    * def account = response
    * def accountId = response.id
