package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/// A node that currently has a role in the cluster layout.
@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record LayoutNodeRole(
        String id,
        String zone,
        @Nullable Long capacity,
        List<String> tags,
        @Nullable Long storedPartitions,
        @Nullable Long usableCapacity
) {
}
