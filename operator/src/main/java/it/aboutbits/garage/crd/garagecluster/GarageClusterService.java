package it.aboutbits.garage.crd.garagecluster;

import it.aboutbits.garage.core.Quantities;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.dto.ApplyClusterLayoutRequest;
import it.aboutbits.garage.core.adminapi.dto.NodeAssignedRole;
import it.aboutbits.garage.core.adminapi.dto.NodeRoleChangeRequest;
import it.aboutbits.garage.core.adminapi.dto.UpdateClusterLayoutRequest;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Singleton
@Slf4j
@NullMarked
public class GarageClusterService {
    /// Idempotent: when the role already matches, no write request is sent.
    public LayoutReconcileResult reconcileLayout(
            GarageAdminApi garageAdminApi,
            GarageClusterLayoutSpec layoutSpec
    ) {
        var clusterStatus = garageAdminApi.getClusterStatus();
        var nodes = clusterStatus.nodes();

        if (nodes.isEmpty()) {
            return LayoutReconcileResult.noNodes();
        }

        // Only single-node clusters are assigned a layout: the Helm chart deploys replication_factor=1.
        if (nodes.size() > 1) {
            return LayoutReconcileResult.unsupportedTopology(nodes.size());
        }

        var node = nodes.getFirst();

        if (!node.isUp()) {
            return LayoutReconcileResult.nodeDown(
                    node.id(),
                    clusterStatus.layoutVersion()
            );
        }

        var desiredRole = desiredRole(layoutSpec);

        if (rolesMatch(node.role(), desiredRole)) {
            return LayoutReconcileResult.upToDate(
                    node.id(),
                    clusterStatus.layoutVersion()
            );
        }

        return applyRole(
                garageAdminApi,
                node.id(),
                desiredRole
        );
    }

    NodeAssignedRole desiredRole(GarageClusterLayoutSpec layoutSpec) {
        return new NodeAssignedRole(
                layoutSpec.getZone(),
                Quantities.toBytes(layoutSpec.getCapacity(), "layout capacity"),
                List.copyOf(layoutSpec.getTags())
        );
    }

    /// Tags are compared as a set, since their order carries no meaning.
    boolean rolesMatch(
            @Nullable NodeAssignedRole currentRole,
            NodeAssignedRole desiredRole
    ) {
        if (currentRole == null) {
            return false;
        }

        return Objects.equals(currentRole.zone(), desiredRole.zone())
                && Objects.equals(currentRole.capacity(), desiredRole.capacity())
                && new HashSet<>(currentRole.tags()).equals(new HashSet<>(desiredRole.tags()));
    }

    private LayoutReconcileResult applyRole(
            GarageAdminApi garageAdminApi,
            String nodeId,
            NodeAssignedRole desiredRole
    ) {
        // GetClusterLayout, not the cluster status, is authoritative for the version to apply.
        var currentLayout = garageAdminApi.getClusterLayout();

        log.info(
                "Staging Garage cluster layout role [node.id={}, layout.version={}, zone={}, capacity={}]",
                nodeId,
                currentLayout.version(),
                desiredRole.zone(),
                desiredRole.capacity()
        );

        garageAdminApi.updateClusterLayout(
                new UpdateClusterLayoutRequest(
                        List.of(
                                NodeRoleChangeRequest.assign(nodeId, desiredRole)
                        )
                )
        );

        // Garage requires the new version to be spelled out as a safety measure.
        var newVersion = currentLayout.version() + 1;

        var applied = garageAdminApi.applyClusterLayout(
                new ApplyClusterLayoutRequest(newVersion)
        );

        log.info(
                "Applied Garage cluster layout [node.id={}, layout.version={}]",
                nodeId,
                applied.layout().version()
        );

        return LayoutReconcileResult.applied(
                nodeId,
                applied.layout().version(),
                "Applied cluster layout version %d".formatted(applied.layout().version())
        );
    }
}
