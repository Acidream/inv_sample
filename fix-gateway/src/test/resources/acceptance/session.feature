Feature: The FIX session: waiting while it is down, asking after every logon (ADR 0027)

  Background:
    * hooks.resetClock()
    * hooks.brokerRefusesLogons(false)
    * def orderId = hooks.orderId()
    * def today = hooks.today()

  Scenario: orders wait while the session is down and go out once it is back
    * hooks.brokerRefusesLogons(true)
    * hooks.brokerDisconnects()
    * hooks.buy(orderId, 'IE00B4L5Y983', '100.00', today)
    * hooks.sleep(2000)
    * match hooks.fixOrdersReceived(orderId) == 0
    * hooks.brokerRefusesLogons(false)
    * def nos = hooks.awaitFixOrder(orderId)
    * match nos['11'] == orderId

  Scenario: after a logon the gateway asks about open orders; one the broker lost is rejected, so its money is freed
    * hooks.buy(orderId, 'IE00B4L5Y983', '100.00', today)
    * def nos = hooks.awaitFixOrder(orderId)
    * hooks.fixAccepted(orderId)
    * def accepted = hooks.awaitExecution(orderId)

    * hooks.brokerForgets(orderId)
    * hooks.brokerDisconnects()
    * def request = hooks.awaitFixStatusRequest(orderId)
    * match request contains { '11': '#(orderId)', '54': '1', '48': 'IE00B4L5Y983' }
    * def report = hooks.awaitExecution(orderId)
    * match report == { type: 'ORDER_REJECTED', reason: 'the broker does not know the order' }

  Scenario: an order the broker still has is left alone after a logon
    * hooks.sell(orderId, 'DE0007164600', '2', today)
    * def nos = hooks.awaitFixOrder(orderId)
    * hooks.brokerDisconnects()
    * def request = hooks.awaitFixStatusRequest(orderId)
    * match hooks.noExecutionWithin(orderId, 1000) == true
    * hooks.fixFilled(orderId, 's1', '2', '160', true)
    * def fill = hooks.awaitExecution(orderId)
    * match fill contains { execId: 's1', done: true }
