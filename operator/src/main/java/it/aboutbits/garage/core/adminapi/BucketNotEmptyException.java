package it.aboutbits.garage.core.adminapi;

import lombok.Getter;
import org.jspecify.annotations.NullMarked;

/// A distinct type because refusing to delete a non-empty bucket is reported differently from a
/// transient failure.
@Getter
@NullMarked
public class BucketNotEmptyException extends RuntimeException {
    private final String bucketId;

    public BucketNotEmptyException(String bucketId) {
        super("The bucket still holds objects and was not deleted [bucket.id=%s]".formatted(bucketId));
        this.bucketId = bucketId;
    }
}
