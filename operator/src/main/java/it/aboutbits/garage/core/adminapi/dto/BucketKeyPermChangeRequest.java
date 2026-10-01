package it.aboutbits.garage.core.adminapi.dto;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record BucketKeyPermChangeRequest(
        String bucketId,
        String accessKeyId,
        ApiBucketKeyPerm permissions
) {
}
