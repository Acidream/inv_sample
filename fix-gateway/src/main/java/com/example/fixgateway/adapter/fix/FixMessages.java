package com.example.fixgateway.adapter.fix;

import com.example.fixgateway.application.BrokerEvent;
import com.example.fixgateway.domain.BrokerOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import quickfix.FieldNotFound;
import quickfix.field.Account;
import quickfix.field.CashOrderQty;
import quickfix.field.ClOrdID;
import quickfix.field.Currency;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.SecurityID;
import quickfix.field.SecurityIDSource;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderStatusRequest;

/**
 * FIX 4.4 to and from the gateway's terms (ADR 0027). Instruments are named by ISIN
 * ({@code SecurityIDSource=4}); {@code Symbol}, required by the dictionary, carries the ISIN too. A
 * buy is a market order for an amount of money ({@code CashOrderQty}), a sell for a quantity, both
 * good for the day.
 */
final class FixMessages {

    private FixMessages() {
    }

    static NewOrderSingle newOrder(BrokerOrder order, String account, Instant now) {
        var placement = order.placement();
        var message = new NewOrderSingle(new ClOrdID(order.orderId()), side(order),
                new TransactTime(LocalDateTime.ofInstant(now, ZoneOffset.UTC)), new OrdType(OrdType.MARKET));
        message.set(new Account(account));
        message.set(new Symbol(placement.isin()));
        message.set(new SecurityID(placement.isin()));
        message.set(new SecurityIDSource(SecurityIDSource.ISIN_NUMBER));
        message.set(new Currency("EUR"));
        message.set(new TimeInForce(TimeInForce.DAY));
        // Written as exact decimal strings: QuickFIX/J's typed setters take doubles.
        if (placement.side() == BrokerOrder.Side.BUY) {
            message.setString(CashOrderQty.FIELD, placement.cashAmount().toPlainString());
        } else {
            message.setString(OrderQty.FIELD, placement.quantity().stripTrailingZeros().toPlainString());
        }
        return message;
    }

    static OrderStatusRequest statusRequest(BrokerOrder order) {
        var message = new OrderStatusRequest(new ClOrdID(order.orderId()), side(order));
        message.set(new Symbol(order.placement().isin()));
        message.set(new SecurityID(order.placement().isin()));
        message.set(new SecurityIDSource(SecurityIDSource.ISIN_NUMBER));
        return message;
    }

    /** @return empty for a report the gateway has no use for (e.g. a trade correction, a status of a new order) */
    static Optional<BrokerEvent> event(ExecutionReport report) throws FieldNotFound {
        var orderId = report.getClOrdID().getValue();
        var ordStatus = report.getOrdStatus().getValue();
        BrokerEvent event = switch (report.getExecType().getValue()) {
            case ExecType.NEW -> new BrokerEvent.Accepted(orderId);
            case ExecType.TRADE, ExecType.PARTIAL_FILL, ExecType.FILL -> new BrokerEvent.Filled(orderId,
                    report.getExecID().getValue(), decimal(report.getString(LastQty.FIELD)),
                    decimal(report.getString(LastPx.FIELD)), ordStatus == OrdStatus.FILLED, tradedAt(report));
            case ExecType.REJECTED -> new BrokerEvent.Rejected(orderId, text(report),
                    rejectReason(report) == OrdRejReason.DUPLICATE_ORDER);
            case ExecType.EXPIRED -> new BrokerEvent.Expired(orderId);
            case ExecType.CANCELED -> new BrokerEvent.Cancelled(orderId, text(report));
            case ExecType.ORDER_STATUS -> new BrokerEvent.Status(orderId,
                    !(ordStatus == OrdStatus.REJECTED && rejectReason(report) == OrdRejReason.UNKNOWN_ORDER));
            default -> null;
        };
        return Optional.ofNullable(event);
    }

    private static Side side(BrokerOrder order) {
        return new Side(order.placement().side() == BrokerOrder.Side.BUY ? Side.BUY : Side.SELL);
    }

    /** As written on the wire, without trailing zeros: "96", "2.083333". */
    private static BigDecimal decimal(String value) {
        var number = new BigDecimal(value).stripTrailingZeros();
        return number.scale() < 0 ? number.setScale(0) : number;
    }

    private static Instant tradedAt(ExecutionReport report) throws FieldNotFound {
        return report.isSetTransactTime()
                ? report.getTransactTime().getValue().toInstant(ZoneOffset.UTC)
                : Instant.now();
    }

    private static int rejectReason(ExecutionReport report) throws FieldNotFound {
        return report.isSetOrdRejReason() ? report.getOrdRejReason().getValue() : -1;
    }

    private static String text(ExecutionReport report) throws FieldNotFound {
        return report.isSetField(Text.FIELD) ? report.getString(Text.FIELD) : "";
    }
}
