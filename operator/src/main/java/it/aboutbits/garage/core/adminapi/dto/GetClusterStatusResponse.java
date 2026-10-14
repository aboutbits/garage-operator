package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record GetClusterStatusResponse(
        long layoutVersion,
        List<NodeResp> nodes
) {
}
