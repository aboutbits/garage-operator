package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NullMarked;

@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
public record ListKeysResponseItem(
        String id,
        String name
) {
}
