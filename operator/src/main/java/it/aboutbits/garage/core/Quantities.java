package it.aboutbits.garage.core;

import io.fabric8.kubernetes.api.model.Quantity;
import org.jspecify.annotations.NullMarked;

/// Converts Kubernetes quantity strings (`20Gi`, `1G`) into bytes.
@NullMarked
public final class Quantities {
    public static long toBytes(
            String value,
            String field
    ) {
        long bytes;

        try {
            bytes = Quantity.getAmountInBytes(Quantity.parse(value)).longValueExact();
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "The %s is not a valid Kubernetes quantity [%s=%s]".formatted(field, field, value),
                    e
            );
        }

        if (bytes <= 0) {
            throw new IllegalArgumentException(
                    "The %s must be greater than zero [%s=%s]".formatted(field, field, value)
            );
        }

        return bytes;
    }

    private Quantities() {
    }
}
