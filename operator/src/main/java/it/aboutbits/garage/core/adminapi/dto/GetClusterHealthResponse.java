package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;

@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record GetClusterHealthResponse(
        // One of `healthy`, `degraded` or `unavailable`.
        String status,
        int knownNodes,
        int connectedNodes,
        int storageNodes,
        int storageNodesUp,
        int partitions,
        int partitionsQuorum,
        int partitionsAllOk
) {
}
