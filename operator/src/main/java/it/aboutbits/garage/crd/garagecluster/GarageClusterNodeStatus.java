package it.aboutbits.garage.crd.garagecluster;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A node as the Garage node behind the admin endpoint sees it.
@Getter
@Setter
@Accessors(chain = true)
@NullMarked
public class GarageClusterNodeStatus {
    private String id = "";

    private @Nullable String hostname = null;

    private boolean up = false;

    /// Part of an older layout version, and still handing its data over to the nodes of the current one.
    private boolean draining = false;

    /// `null` while the node has no role.
    private @Nullable String zone = null;

    /// In bytes. `null` for a gateway node, or a node without a role.
    private @Nullable Long capacity = null;
}
