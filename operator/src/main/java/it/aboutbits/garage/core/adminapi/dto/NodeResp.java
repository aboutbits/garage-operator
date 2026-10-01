package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record NodeResp(
        String id,
        @JsonProperty("isUp") boolean isUp,
        // Part of an older layout version and still draining data.
        boolean draining,
        @Nullable String hostname,
        @Nullable String garageVersion,
        // `null` until the node is assigned a role.
        @Nullable NodeAssignedRole role
) {
}
