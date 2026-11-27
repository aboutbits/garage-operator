package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;

/// The parameters Garage computes a layout with, as opposed to the roles of the nodes.
@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record LayoutParameters(
        ZoneRedundancy zoneRedundancy
) {
}
