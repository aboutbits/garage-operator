package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@NullMarked
public record UpdateClusterLayoutRequest(
        List<NodeRoleChangeRequest> roles,
        // `null` leaves the parameters as they are.
        @Nullable LayoutParameters parameters
) {
}
