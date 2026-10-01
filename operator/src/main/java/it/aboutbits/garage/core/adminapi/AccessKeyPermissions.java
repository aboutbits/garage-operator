package it.aboutbits.garage.core.adminapi;

import org.jspecify.annotations.NullMarked;

/// Cluster-wide permissions of an access key, as opposed to per-bucket grants.
@NullMarked
public record AccessKeyPermissions(
        boolean allowCreateBucket
) {
    public static final AccessKeyPermissions NONE = new AccessKeyPermissions(false);
}
