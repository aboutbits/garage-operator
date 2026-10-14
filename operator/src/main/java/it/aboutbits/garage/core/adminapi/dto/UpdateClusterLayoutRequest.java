package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@NullMarked
public record UpdateClusterLayoutRequest(
        List<NodeRoleChangeRequest> roles
) {
}
