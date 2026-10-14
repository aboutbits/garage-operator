package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/// A node's layout role. A `null` [#capacity] marks a gateway node, which stores no data.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@NullMarked
public record NodeAssignedRole(
        String zone,
        @Nullable Long capacity,
        List<String> tags
) {
}
