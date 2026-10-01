package it.aboutbits.garage.crd.bucketaccess;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;

@RequiredArgsConstructor
@NullMarked
public enum BucketPermission {
    /// List and download objects.
    READ("read"),
    /// Upload, overwrite and delete objects.
    WRITE("write"),
    /// Change the bucket's configuration (website, CORS); grants neither `read` nor `write`.
    OWNER("owner");

    private final String permission;

    @JsonValue
    public String toValue() {
        return permission;
    }
}
