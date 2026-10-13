package it.aboutbits.garage.core;

import it.aboutbits.garage._support.valuesource.BlankSource;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@NullMarked
class QuantitiesTest {
    @ParameterizedTest
    @CsvSource({
            "20Gi, 21474836480",
            "1Gi, 1073741824",
            "500Mi, 524288000",
            "1G, 1000000000",
            "1024, 1024"
    })
    @DisplayName("Kubernetes quantities should be converted to bytes")
    void quantity_isConvertedToBytes(
            String value,
            long expectedBytes
    ) {
        // when
        var bytes = Quantities.toBytes(value, "spec.capacity");

        // then
        assertThat(bytes).isEqualTo(expectedBytes);
    }

    @ParameterizedTest
    @BlankSource
    @ValueSource(strings = {"twenty", "20Gb", "-1Gi", "0"})
    @DisplayName("An unusable quantity should be rejected, naming the field it came from")
    void invalidQuantity_isRejected(String value) {
        // when
        var thrown = catchThrowable(() -> Quantities.toBytes(value, "spec.quotas.maxSize"));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[spec.quotas.maxSize=%s]".formatted(value));
    }
}
