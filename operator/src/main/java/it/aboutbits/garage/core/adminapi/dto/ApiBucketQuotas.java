package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A `null` component means no limit. Always serialize nulls: clearing a quota needs an explicit `null`.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
@NullMarked
public record ApiBucketQuotas(
        @Nullable Long maxSize,
        @Nullable Long maxObjects
) {
}
