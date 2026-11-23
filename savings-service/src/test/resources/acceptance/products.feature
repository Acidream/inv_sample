Feature: Savings products and their versions

  Background:
    * url baseUrl

  Scenario: the products on sale are listed with the terms of their current version
    * hooks.resetClock()
    Given path 'products'
    When method get
    Then status 200
    And match response.items contains
      """
      [
        { code: 'INSTANT_ACCESS', version: 1, name: '#string', annualRate: '0.0250', payoutFrequency: 'MONTHLY' },
        { code: 'QUARTERLY_INTEREST', version: 1, name: '#string', annualRate: '0.0275', payoutFrequency: 'QUARTERLY' },
        { code: 'YEARLY_INTEREST', version: 1, name: '#string', annualRate: '0.0300', payoutFrequency: 'YEARLY' }
      ]
      """

  Scenario: a product taken off sale is no longer listed
    * hooks.resetClock()
    * def productCode = hooks.product(0.02, 'MONTHLY')
    * hooks.stopSelling(productCode)
    Given path 'products'
    When method get
    Then match response.items[*].code !contains productCode

  Scenario: a new version applies to accounts opened from the day it takes effect; earlier accounts keep theirs
    * hooks.setClock('2026-03-15T09:00:00Z')
    * def productCode = hooks.product(0.02, 'MONTHLY')
    * hooks.productVersion(productCode, 2, 0.03, 'QUARTERLY', '2026-04-01T00:00:00+02:00')

    Given path 'accounts'
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)' }
    When method post
    Then status 201
    And match response.product contains { version: 1, annualRate: '0.0200', payoutFrequency: 'MONTHLY' }
    * def before = response.id

    * hooks.setClock('2026-04-01T09:00:00Z')
    Given path 'products'
    When method get
    Then match response.items contains { code: '#(productCode)', version: 2, name: '#string', annualRate: '0.0300', payoutFrequency: 'QUARTERLY' }

    Given path 'accounts'
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)' }
    When method post
    Then status 201
    And match response.product contains { version: 2, annualRate: '0.0300', payoutFrequency: 'QUARTERLY' }

    Given path 'accounts', before
    When method get
    Then match response.product contains { version: 1, annualRate: '0.0200', payoutFrequency: 'MONTHLY' }
