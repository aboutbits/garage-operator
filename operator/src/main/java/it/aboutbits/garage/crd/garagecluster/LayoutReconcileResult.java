package it.aboutbits.garage.crd.garagecluster;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Mapping the outcome onto a phase and a reschedule is left to [GarageClusterReconciler].
@NullMarked
public record LayoutReconcileResult(
        Outcome outcome,
        @Nullable String nodeId,
        long layoutVersion,
        String message
) {
    public enum Outcome {
        NO_NODES,
        NODE_DOWN,
        UNSUPPORTED_TOPOLOGY,
        UP_TO_DATE,
        APPLIED
    }

    public static LayoutReconcileResult noNodes() {
        return new LayoutReconcileResult(
                Outcome.NO_NODES,
                null,
                0,
                "The Garage cluster reports no nodes yet"
        );
    }

    public static LayoutReconcileResult nodeDown(
            String nodeId,
            long layoutVersion
    ) {
        return new LayoutReconcileResult(
                Outcome.NODE_DOWN,
                nodeId,
                layoutVersion,
                "The Garage node is not connected [node.id=%s]".formatted(nodeId)
        );
    }

    public static LayoutReconcileResult unsupportedTopology(int nodeCount) {
        return new LayoutReconcileResult(
                Outcome.UNSUPPORTED_TOPOLOGY,
                null,
                0,
                "The Garage cluster reports %d nodes, but this operator only assigns layout roles for single-node clusters. Assign the layout manually, or open an issue if multi-node support is needed.".formatted(
                        nodeCount
                )
        );
    }

    public static LayoutReconcileResult upToDate(
            String nodeId,
            long layoutVersion
    ) {
        return new LayoutReconcileResult(
                Outcome.UP_TO_DATE,
                nodeId,
                layoutVersion,
                "The cluster layout is up-to-date"
        );
    }

    public static LayoutReconcileResult applied(
            String nodeId,
            long layoutVersion,
            String message
    ) {
        return new LayoutReconcileResult(
                Outcome.APPLIED,
                nodeId,
                layoutVersion,
                message
        );
    }
}
