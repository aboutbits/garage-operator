package it.aboutbits.garage.crd.garagecluster;

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
public class GarageClusterStatus extends CRStatus {
    /// `0` means no layout has been applied yet.
    private long layoutVersion = 0;

    private @Nullable String nodeId = null;

    /// `healthy`, `degraded` or `unavailable`.
    private @Nullable String health = null;

    private int storageNodes = 0;

    private int storageNodesUp = 0;
}
