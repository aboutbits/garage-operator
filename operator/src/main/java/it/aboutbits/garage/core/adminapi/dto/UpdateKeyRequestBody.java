package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A permission that should end up `false` must be sent under `deny` rather than omitted.
@JsonInclude(JsonInclude.Include.NON_NULL)
@NullMarked
public record UpdateKeyRequestBody(
        @Nullable String name,
        @Nullable KeyPerm allow,
        @Nullable KeyPerm deny
) {
    public static UpdateKeyRequestBody named(String name) {
        return new UpdateKeyRequestBody(name, null, null);
    }

    public static UpdateKeyRequestBody permissions(boolean allowCreateBucket) {
        return new UpdateKeyRequestBody(
                null,
                allowCreateBucket ? new KeyPerm(true) : null,
                allowCreateBucket ? null : new KeyPerm(true)
        );
    }

    public static UpdateKeyRequestBody createKey(
            String name,
            boolean allowCreateBucket
    ) {
        return new UpdateKeyRequestBody(
                name,
                allowCreateBucket ? new KeyPerm(true) : null,
                null
        );
    }
}
