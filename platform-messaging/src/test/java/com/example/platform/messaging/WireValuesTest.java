package com.example.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WireValuesTest {

    @Test
    void anIdMustBePresentShortEnoughAndFreeOfNul() {
        assertThat(WireValues.id("tr-1", "transfer_id", 10)).isEqualTo("tr-1");
        assertThatThrownBy(() -> WireValues.id(" ", "transfer_id", 10)).hasMessage("Missing transfer_id");
        assertThatThrownBy(() -> WireValues.id("x".repeat(11), "transfer_id", 10))
                .hasMessage("transfer_id longer than 10 characters");
        assertThatThrownBy(() -> WireValues.id("a\u0000b", "transfer_id", 10))
                .hasMessage("transfer_id contains a NUL character");
    }

    @Test
    void lengthsCountCharactersNotUtf16Units() {
        assertThat(WireValues.id("😀".repeat(10), "id", 10)).hasSize(20);
    }

    @Test
    void textIsShortenedNotRefused() {
        assertThat(WireValues.text("abcdef", 3)).isEqualTo("abc");
        assertThat(WireValues.text("😀😀😀", 2)).isEqualTo("😀😀");
        assertThat(WireValues.text("a\u0000b", 10)).isEqualTo("a b");
    }

    @Test
    void amountsArePlainDecimalsThatFitTheDatabase() {
        assertThat(WireValues.plainAmount("250.5")).isEqualByComparingTo("250.50");
        assertThat(WireValues.plainAmount("0")).isEqualByComparingTo("0");
        assertThat(WireValues.plainAmount("9".repeat(17) + ".99")).isNotNull();
        for (var bad : new String[] {"-1", "1e3", "1.001", " 1", "1,00", ""}) {
            assertThatThrownBy(() -> WireValues.plainAmount(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WireValues.plainAmount("9".repeat(18))).hasMessageStartingWith("Amount too large");
    }

    @Test
    void timestampsRoundTrip() {
        var instant = Instant.parse("2026-11-23T09:15:00.123456789Z");
        assertThat(WireValues.instant(WireValues.timestamp(instant), "occurred_at")).isEqualTo(instant);
        assertThatThrownBy(() -> WireValues.instant(null, "occurred_at")).hasMessage("Missing occurred_at");
    }

    @Test
    void uuidsMustBePresentAndWellFormed() {
        assertThatThrownBy(() -> WireValues.uuid("", "account_id")).hasMessage("Missing account_id");
        assertThatThrownBy(() -> WireValues.uuid("nope", "account_id")).isInstanceOf(IllegalArgumentException.class);
    }
}
