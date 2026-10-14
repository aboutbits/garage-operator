package it.aboutbits.garage.core.adminapi;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record BucketInfo(
        String id,
        String name,
        BucketQuotas quotas,
        long objects,
        long bytes
) {
}
