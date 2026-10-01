package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record GetKeyInfoResponse(
        String accessKeyId,
        String name,
        // Only present on CreateKey or with `showSecretKey=true`.
        @Nullable String secretAccessKey,
        KeyPerm permissions
) {
}
