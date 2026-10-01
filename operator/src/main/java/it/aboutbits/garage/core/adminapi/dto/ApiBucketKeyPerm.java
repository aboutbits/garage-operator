package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NullMarked;

/// In `AllowBucketKey` and `DenyBucketKey`, a `false` flag leaves the permission untouched.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
@NullMarked
public record ApiBucketKeyPerm(
        boolean read,
        boolean write,
        boolean owner
) {
    public boolean isEmpty() {
        return !read && !write && !owner;
    }
}
