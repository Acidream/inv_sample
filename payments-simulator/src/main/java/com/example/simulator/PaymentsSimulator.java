package com.example.simulator;

import com.example.events.common.v1.Money;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.PaymentReceived;
import com.example.events.payments.v1.WithdrawalCancelled;
import com.example.events.payments.v1.WithdrawalConfirmed;
import com.example.events.payments.v1.WithdrawalRequested;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Stands in for the Payments team's service in local demos. Usage:
 *
 * <pre>
 * deposit  &lt;accountId&gt; &lt;amount&gt;
 * withdraw &lt;accountId&gt; &lt;amount&gt;      (prints the request id)
 * confirm  &lt;accountId&gt; &lt;requestId&gt;
 * cancel   &lt;accountId&gt; &lt;requestId&gt;
 * </pre>
 */
public final class PaymentsSimulator {

    private static final String TOPIC = "payments.savings-instructions.v1";

    private PaymentsSimulator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: deposit|withdraw <accountId> <amount>  |  confirm|cancel <accountId> <requestId>");
            System.exit(2);
        }
        var accountId = args[1];
        var message = PaymentInstruction.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
                .setAccountId(accountId);
        switch (args[0]) {
            case "deposit" -> message.setPaymentReceived(PaymentReceived.newBuilder()
                    .setPaymentId(UUID.randomUUID().toString())
                    .setAmount(eur(args[2])));
            case "withdraw" -> {
                var requestId = UUID.randomUUID().toString();
                message.setWithdrawalRequested(WithdrawalRequested.newBuilder()
                        .setRequestId(requestId)
                        .setAmount(eur(args[2])));
                System.out.println("requestId=" + requestId);
            }
            case "confirm" -> message.setWithdrawalConfirmed(WithdrawalConfirmed.newBuilder().setRequestId(args[2]));
            case "cancel" -> message.setWithdrawalCancelled(WithdrawalCancelled.newBuilder().setRequestId(args[2]));
            default -> throw new IllegalArgumentException("Unknown command " + args[0]);
        }

        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getenv().getOrDefault("KAFKA", "localhost:9092"),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (var producer = new KafkaProducer<String, byte[]>(config)) {
            producer.send(new ProducerRecord<>(TOPIC, accountId, message.build().toByteArray())).get();
        }
        System.out.println("sent " + args[0] + " for account " + accountId);
    }

    private static Money eur(String amount) {
        return Money.newBuilder().setAmount(amount).setCurrency("EUR").build();
    }
}
