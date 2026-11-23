package com.example.simulator;

import com.example.events.currentaccount.v1.CurrentAccountCredited;
import com.example.events.currentaccount.v1.CurrentAccountDebited;
import com.example.events.currentaccount.v1.CurrentAccountEvent;
import com.example.events.currentaccount.v1.CurrentAccountOpened;
import com.example.events.currentaccount.v1.DebitRejected;
import com.example.events.payments.v1.CurrentAccountInstruction;
import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Stands in for the current account service in local demos (ADR 0018). Usage:
 *
 * <pre>
 * open  &lt;customerId&gt;                    opens a current account (prints its id)
 * serve [--reject-debits-over &lt;amount&gt;]   answers the transfer service's instructions until stopped:
 *                                         credits always succeed, debits above the limit are rejected
 * </pre>
 */
public final class CurrentAccountSimulator {

    private static final String INSTRUCTIONS = "payments.current-account-instructions.v1";
    private static final String EVENTS = "current-account.events.v1";

    private CurrentAccountSimulator() {
    }

    public static void main(String[] args) throws Exception {
        var bootstrap = System.getenv().getOrDefault("KAFKA", "localhost:9092");
        try (var producer = new KafkaProducer<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            if (args.length == 2 && args[0].equals("open")) {
                var accountId = UUID.randomUUID().toString();
                publish(producer, accountId, CurrentAccountEvent.newBuilder().setCurrentAccountOpened(
                        CurrentAccountOpened.newBuilder().setCustomerId(UUID.fromString(args[1]).toString())));
                System.out.println("currentAccountId=" + accountId);
            } else if (args.length >= 1 && args[0].equals("serve")) {
                var limit = args.length == 3 && args[1].equals("--reject-debits-over") ? new BigDecimal(args[2]) : null;
                serve(bootstrap, producer, limit);
            } else {
                System.err.println("usage: open <customerId>  |  serve [--reject-debits-over <amount>]");
                System.exit(2);
            }
        }
    }

    private static void serve(String bootstrap, KafkaProducer<String, byte[]> producer, BigDecimal limit)
            throws Exception {
        try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "current-account-simulator",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(INSTRUCTIONS));
            System.out.println("Answering " + INSTRUCTIONS + (limit == null ? "" : ", rejecting debits over " + limit));
            while (true) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    answer(producer, CurrentAccountInstruction.parseFrom(record.value()), limit);
                }
            }
        }
    }

    private static void answer(KafkaProducer<String, byte[]> producer, CurrentAccountInstruction instruction,
            BigDecimal limit) throws Exception {
        var accountId = instruction.getAccountId();
        switch (instruction.getPayloadCase()) {
            case DEBIT_CURRENT_ACCOUNT -> {
                var debit = instruction.getDebitCurrentAccount();
                if (limit != null && new BigDecimal(debit.getAmount().getAmount()).compareTo(limit) > 0) {
                    publish(producer, accountId, CurrentAccountEvent.newBuilder().setDebitRejected(DebitRejected
                            .newBuilder().setTransferId(debit.getTransferId()).setReason("overdraft limit exceeded")));
                    System.out.println("rejected debit " + debit.getTransferId());
                } else {
                    publish(producer, accountId, CurrentAccountEvent.newBuilder().setCurrentAccountDebited(
                            CurrentAccountDebited.newBuilder().setTransferId(debit.getTransferId())
                                    .setAmount(debit.getAmount())));
                    System.out.println("debited " + debit.getAmount().getAmount() + " for " + debit.getTransferId());
                }
            }
            case CREDIT_CURRENT_ACCOUNT -> {
                var credit = instruction.getCreditCurrentAccount();
                publish(producer, accountId, CurrentAccountEvent.newBuilder().setCurrentAccountCredited(
                        CurrentAccountCredited.newBuilder().setTransferId(credit.getTransferId())
                                .setAmount(credit.getAmount())));
                System.out.println("credited " + credit.getAmount().getAmount() + " for " + credit.getTransferId());
            }
            default -> System.err.println("ignoring instruction without payload " + instruction.getEventId());
        }
    }

    private static void publish(KafkaProducer<String, byte[]> producer, String accountId,
            CurrentAccountEvent.Builder event) throws Exception {
        var now = Instant.now();
        var built = event.setEventId(UUID.randomUUID().toString())
                .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                .setAccountId(accountId)
                .build();
        producer.send(new ProducerRecord<>(EVENTS, accountId, built.toByteArray())).get();
    }
}
