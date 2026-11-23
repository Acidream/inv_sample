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

  Scenario: an order is decided as of when it was placed, also when the product went off sale since
    * hooks.setClock('2026-05-10T09:00:00Z')
    * def productCode = hooks.product(0.02, 'MONTHLY')
    * hooks.stopSellingFrom(productCode, '2026-05-10T12:00:00Z')

    * def before = hooks.uuid()
    * def beforeKey = before
    Given path 'orders'
    And header Idempotency-Key = beforeKey
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)', productVersion: 1, amount: '10.00', fromAccountId: '#(hooks.uuid())' }
    When method post
    Then status 202
    * def before = response.orderId
    Given path 'orders', before
    And retry until responseStatus == 200
    When method get
    Then match response.status == 'FUNDING'

    * hooks.setClock('2026-05-10T12:00:00Z')
    * def after = hooks.uuid()
    * def afterKey = after
    Given path 'orders'
    And header Idempotency-Key = afterKey
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)', productVersion: 1, amount: '10.00', fromAccountId: '#(hooks.uuid())' }
    When method post
    Then status 202
    * def after = response.orderId
    Given path 'orders', after
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'PRODUCT_NOT_ON_SALE' }
    Given path 'products'
    When method get
    Then match response.items[*].code !contains productCode

  Scenario: a new version applies to orders placed from the day it takes effect; earlier accounts keep theirs
    * hooks.setClock('2026-03-15T09:00:00Z')
    * def productCode = hooks.product(0.02, 'MONTHLY')
    * hooks.productVersion(productCode, 2, 0.03, 'QUARTERLY', '2026-04-01T00:00:00+02:00')

    * def before = hooks.uuid()
    * def beforeKey = before
    Given path 'orders'
    And header Idempotency-Key = beforeKey
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)', productVersion: 1, amount: '10.00', fromAccountId: '#(hooks.uuid())' }
    When method post
    Then status 202
    * def before = response.orderId
    Given path 'orders', before
    And retry until responseStatus == 200
    When method get
    Then match response.status == 'FUNDING'
    * def beforeAccount = response.accountId

    * hooks.setClock('2026-04-01T09:00:00Z')
    Given path 'products'
    When method get
    Then match response.items contains { code: '#(productCode)', version: 2, name: '#string', annualRate: '0.0300', payoutFrequency: 'QUARTERLY' }

    # Terms the customer saw before the change are not sold any more
    * def stale = hooks.uuid()
    * def staleKey = stale
    Given path 'orders'
    And header Idempotency-Key = staleKey
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)', productVersion: 1, amount: '10.00', fromAccountId: '#(hooks.uuid())' }
    When method post
    Then status 202
    * def stale = response.orderId
    Given path 'orders', stale
    And retry until responseStatus == 200
    When method get
    Then match response contains { status: 'REJECTED', rejectionReason: 'PRODUCT_VERSION_NOT_CURRENT', accountId: null }

    * def after = hooks.uuid()
    * def afterKey = after
    Given path 'orders'
    And header Idempotency-Key = afterKey
    And request { customerId: '#(hooks.uuid())', productCode: '#(productCode)', productVersion: 2, amount: '10.00', fromAccountId: '#(hooks.uuid())' }
    When method post
    Then status 202
    * def after = response.orderId
    Given path 'orders', after
    And retry until responseStatus == 200
    When method get
    Then match response.status == 'FUNDING'
    Given path 'accounts', response.accountId
    When method get
    Then match response.product contains { version: 2, annualRate: '0.0300', payoutFrequency: 'QUARTERLY' }

    Given path 'accounts', beforeAccount
    When method get
    Then match response.product contains { version: 1, annualRate: '0.0200', payoutFrequency: 'MONTHLY' }
