package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record GetClusterLayoutResponse(
        long version,
        List<LayoutNodeRole> roles,
        LayoutParameters parameters,
        List<NodeRoleChangeRequest> stagedRoleChanges,
        // `null` unless staged parameters differ from the current ones.
        @Nullable LayoutParameters stagedParameters
) {
}
