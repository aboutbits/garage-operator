package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;

@JsonInclude(JsonInclude.Include.NON_NULL)
@NullMarked
public record CreateBucketRequest(
        String globalAlias
) {
}
