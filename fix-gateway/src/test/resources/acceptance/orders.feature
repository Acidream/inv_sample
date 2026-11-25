Feature: Orders over FIX 4.4, reports back on Kafka (ADR 0027)

  Background:
    * hooks.resetClock()
    * hooks.brokerRefusesLogons(false)
    * def orderId = hooks.orderId()
    * def today = hooks.today()

  Scenario: a buy becomes a market NewOrderSingle for an amount of money; its reports come back in order
    * hooks.buy(orderId, 'IE00B4L5Y983', '500.00', today)
    * def nos = hooks.awaitFixOrder(orderId)
    # 1 Account, 15 Currency, 22 SecurityIDSource (4 = ISIN), 40 OrdType (1 = market), 48 SecurityID,
    # 54 Side (1 = buy), 55 Symbol, 59 TimeInForce (0 = day), 152 CashOrderQty
    * match nos contains { '1': 'OMNIBUS-1', '11': '#(orderId)', '15': 'EUR', '22': '4', '40': '1', '48': 'IE00B4L5Y983', '54': '1', '55': 'IE00B4L5Y983', '59': '0', '152': '500.00' }
    * assert nos['38'] == null

    * hooks.fixAccepted(orderId)
    * def accepted = hooks.awaitExecution(orderId)
    * match accepted.type == 'ORDER_ACCEPTED'
    * hooks.fixFilled(orderId, 'e1', '3.125', '96.0000', false)
    * hooks.fixFilled(orderId, 'e2', '2.083333', '96', true)
    * def first = hooks.awaitExecution(orderId)
    * match first == { type: 'ORDER_FILLED', execId: 'e1', quantity: '3.125', price: '96', tradedAt: '2026-11-24T09:30:00Z' }
    * def last = hooks.awaitExecution(orderId)
    * match last == { type: 'ORDER_FILLED', execId: 'e2', quantity: '2.083333', price: '96', done: true, tradedAt: '#string' }

  Scenario: a sell names its quantity
    * hooks.sell(orderId, 'DE0007164600', '1.5', today)
    * def nos = hooks.awaitFixOrder(orderId)
    * match nos contains { '54': '2', '38': '1.5', '48': 'DE0007164600' }
    * assert nos['152'] == null

  Scenario: an order sent again is placed once; another order under its id is dead-lettered
    * def messageId = hooks.buy(orderId, 'IE00B4L5Y983', '100.00', today)
    * def nos = hooks.awaitFixOrder(orderId)
    * hooks.buy(orderId, 'IE00B4L5Y983', '100.00', today)
    * hooks.sleep(1000)
    * match hooks.fixOrdersReceived(orderId) == 0

    * hooks.buy(orderId, 'IE00B4L5Y983', '100.01', today)
    * def cause = hooks.awaitDeadLetter('broker.orders.v1', orderId)
    * match cause == 'com.example.fixgateway.application.OrderIdInUseException'

  Scenario: a fill the broker sends again with PossDupFlag is reported once
    * hooks.buy(orderId, 'IE00B4L5Y983', '100.00', today)
    * def nos = hooks.awaitFixOrder(orderId)
    * hooks.fixFilled(orderId, 'd1', '0.5', '100', false)
    * hooks.fixFilledAgain(orderId, 'd1', '0.5', '100', false)
    * hooks.fixFilled(orderId, 'd2', '0.5', '100', true)
    * def first = hooks.awaitExecution(orderId)
    * match first.execId == 'd1'
    * def second = hooks.awaitExecution(orderId)
    * match second.execId == 'd2'

  Scenario Outline: how an order can end without a full fill - <case>
    * hooks.buy(orderId, 'IE00B4L5Y983', '100.00', today)
    * def nos = hooks.awaitFixOrder(orderId)
    * eval <broker>
    * def report = hooks.awaitExecution(orderId)
    * match report == <expected>

    Examples:
      | case      | broker                                         | expected                                                                 |
      | rejected  | hooks.fixRejected(orderId, 'market closed', false) | { type: 'ORDER_REJECTED', reason: 'market closed' }                   |
      | expired   | hooks.fixExpired(orderId)                      | { type: 'ORDER_EXPIRED' }                                                |
      | cancelled | hooks.fixCancelled(orderId, 'instrument halted') | { type: 'ORDER_REJECTED', reason: 'cancelled by the broker: instrument halted' } |

  Scenario: the broker rejecting a duplicate of an order it has is not a rejection of the order
    * hooks.buy(orderId, 'IE00B4L5Y983', '100.00', today)
    * def nos = hooks.awaitFixOrder(orderId)
    * hooks.fixRejected(orderId, 'duplicate order', true)
    * match hooks.noExecutionWithin(orderId, 1000) == true
    * hooks.fixExpired(orderId)
    * def report = hooks.awaitExecution(orderId)
    * match report.type == 'ORDER_EXPIRED'

  Scenario: an order whose trading day has already ended expires without reaching the broker
    * hooks.buy(orderId, 'IE00B4L5Y983', '100.00', '2026-01-02')
    * def report = hooks.awaitExecution(orderId)
    * match report.type == 'ORDER_EXPIRED'
    * match hooks.fixOrdersReceived(orderId) == 0

  Scenario Outline: malformed orders are dead-lettered - <case>
    * eval <order>
    * def cause = hooks.awaitDeadLetter('broker.orders.v1', orderId)
    * match cause == 'com.example.platform.messaging.InvalidMessageException'

    Examples:
      | case          | order                                                          |
      | not an ISIN   | hooks.buy(orderId, 'APPLE', '100.00', today)                   |
      | zero cash     | hooks.buy(orderId, 'IE00B4L5Y983', '0.00', today)              |
      | bad quantity  | hooks.sell(orderId, 'IE00B4L5Y983', '1.0000001', today)        |
      | no day        | hooks.buy(orderId, 'IE00B4L5Y983', '1.00', 'tomorrow')         |
