package it.aboutbits.garage.core.adminapi;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A `null` component means no limit.
@NullMarked
public record BucketQuotas(
        @Nullable Long maxSizeBytes,
        @Nullable Long maxObjects
) {
    public static final BucketQuotas NONE = new BucketQuotas(null, null);

    public boolean isEmpty() {
        return maxSizeBytes == null && maxObjects == null;
    }
}
