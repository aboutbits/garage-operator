package it.aboutbits.garage.core.adminapi;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record BucketPermissions(
        boolean read,
        boolean write,
        boolean owner
) {
    public static final BucketPermissions NONE = new BucketPermissions(false, false, false);

    public boolean isEmpty() {
        return !read && !write && !owner;
    }
}
