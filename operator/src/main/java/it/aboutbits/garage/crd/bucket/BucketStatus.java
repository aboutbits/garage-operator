package it.aboutbits.garage.crd.bucket;

import it.aboutbits.garage.core.CRStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Getter
@Setter
@Accessors(chain = true)
@NullMarked
public class BucketStatus extends CRStatus {
    private @Nullable String bucketId = null;

    private long objects = 0;

    private long bytes = 0;
}
