package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;

/// Cluster-wide permissions of an access key.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
@NullMarked
public record KeyPerm(
        boolean createBucket
) {
}
