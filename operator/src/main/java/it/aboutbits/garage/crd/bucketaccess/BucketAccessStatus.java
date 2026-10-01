package it.aboutbits.garage.crd.bucketaccess;

import it.aboutbits.garage.core.CRStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Records the granted ids and connection, so revoking works after `Bucket`/`AccessKey` are gone.
@Getter
@Setter
@Accessors(chain = true)
@NullMarked
public class BucketAccessStatus extends CRStatus {
    private @Nullable String bucketId = null;

    private @Nullable String accessKeyId = null;

    private @Nullable String connectionNamespace = null;

    private @Nullable String connectionName = null;
}
