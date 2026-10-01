package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record GetClusterLayoutResponse(
        long version,
        List<LayoutNodeRole> roles,
        List<NodeRoleChangeRequest> stagedRoleChanges
) {
}
