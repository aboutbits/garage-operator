package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;

/// Only quotas are sent, so CORS, lifecycle and website settings configured out of band survive.
@JsonInclude(JsonInclude.Include.NON_NULL)
@NullMarked
public record UpdateBucketRequestBody(
        ApiBucketQuotas quotas
) {
}
