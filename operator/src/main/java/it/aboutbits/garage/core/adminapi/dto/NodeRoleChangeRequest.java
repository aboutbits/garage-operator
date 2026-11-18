package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/// A staged role change: a union of removal and role assignment. `NON_NULL` keeps the unused half out.
@JsonInclude(JsonInclude.Include.NON_NULL)
@NullMarked
public record NodeRoleChangeRequest(
        String id,
        @Nullable String zone,
        @Nullable Long capacity,
        @Nullable List<String> tags,
        @Nullable Boolean remove
) {
    public static NodeRoleChangeRequest assign(
            String id,
            NodeAssignedRole role
    ) {
        return new NodeRoleChangeRequest(
                id,
                role.zone(),
                role.capacity(),
                role.tags(),
                null
        );
    }

    public static NodeRoleChangeRequest remove(String id) {
        return new NodeRoleChangeRequest(
                id,
                null,
                null,
                null,
                true
        );
    }
}
