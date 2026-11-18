package it.aboutbits.garage.crd.garagecluster;

import org.jspecify.annotations.NullMarked;

import java.util.List;

/// Mapping the outcome onto a phase and a reschedule is left to [GarageClusterReconciler].
///
/// An empty [#nodes] means that the recorded nodes are kept.
@NullMarked
public record LayoutReconcileResult(
        Outcome outcome,
        List<GarageClusterNodeStatus> nodes,
        long layoutVersion,
        String message
) {
    public enum Outcome {
        AWAITING_NODES,
        OUTDATED_NODE,
        REJECTED,
        UP_TO_DATE,
        APPLIED
    }

    public static LayoutReconcileResult awaitingNodes(
            List<GarageClusterNodeStatus> nodes,
            long connectedNodes,
            int minNodes,
            long layoutVersion
    ) {
        return new LayoutReconcileResult(
                Outcome.AWAITING_NODES,
                nodes,
                layoutVersion,
                "Waiting for the Garage nodes to connect [connectedNodes=%d, spec.minNodes=%d]".formatted(
                        connectedNodes,
                        minNodes
                )
        );
    }

    /// Keeps the recorded nodes: they describe the cluster better than the node that answered.
    ///
    /// @param knownLayoutVersion the newest version recorded in the status or reported by another node
    public static LayoutReconcileResult outdatedNode(
            long reportedLayoutVersion,
            long knownLayoutVersion
    ) {
        return new LayoutReconcileResult(
                Outcome.OUTDATED_NODE,
                List.of(),
                knownLayoutVersion,
                "The Garage node behind the admin endpoint reports an older layout than known, so it is not connected to the rest of the cluster [layoutVersion=%d, knownLayoutVersion=%d]".formatted(
                        reportedLayoutVersion,
                        knownLayoutVersion
                )
        );
    }

    public static LayoutReconcileResult rejected(
            List<GarageClusterNodeStatus> nodes,
            long layoutVersion,
            String error
    ) {
        return new LayoutReconcileResult(
                Outcome.REJECTED,
                nodes,
                layoutVersion,
                "Garage rejected the cluster layout, nothing was applied [error=%s]".formatted(error)
        );
    }

    public static LayoutReconcileResult upToDate(
            List<GarageClusterNodeStatus> nodes,
            long layoutVersion
    ) {
        return new LayoutReconcileResult(
                Outcome.UP_TO_DATE,
                nodes,
                layoutVersion,
                "The cluster layout is up-to-date"
        );
    }

    public static LayoutReconcileResult applied(
            List<GarageClusterNodeStatus> nodes,
            long layoutVersion
    ) {
        return new LayoutReconcileResult(
                Outcome.APPLIED,
                nodes,
                layoutVersion,
                "Applied cluster layout version %d".formatted(layoutVersion)
        );
    }
}
