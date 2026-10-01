package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record GetBucketInfoResponse(
        String id,
        List<String> globalAliases,
        ApiBucketQuotas quotas,
        long objects,
        long bytes,
        List<GetBucketInfoKey> keys
) {
}
