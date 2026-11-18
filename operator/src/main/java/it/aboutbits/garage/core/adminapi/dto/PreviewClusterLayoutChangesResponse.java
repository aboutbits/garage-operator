package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Either an [#error], or the computed layout, which the operator does not read.
@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record PreviewClusterLayoutChangesResponse(
        // Why Garage cannot compute a layout from the staged changes, such as too few nodes for the replication factor.
        @Nullable String error
) {
}
