package it.aboutbits.garage.core.adminapi;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record AccessKeyInfo(
        String accessKeyId,
        String name,
        // `null` when Garage was not asked to reveal it.
        @Nullable String secretAccessKey,
        AccessKeyPermissions permissions
) {
}
