package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage.core.Quantities;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.dto.ApplyClusterLayoutRequest;
import it.aboutbits.garage.core.adminapi.dto.NodeAssignedRole;
import it.aboutbits.garage.core.adminapi.dto.NodeRoleChangeRequest;
import it.aboutbits.garage.core.adminapi.dto.UpdateClusterLayoutRequest;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Singleton
@RequiredArgsConstructor
@Slf4j
@NullMarked
public class GarageClusterService {
    private final KubernetesClient kubernetesClient;

    /// Idempotent: when the role already matches, no write request is sent.
    public LayoutReconcileResult reconcileLayout(
            GarageAdminApi garageAdminApi,
            GarageCluster garageCluster
    ) {
        var clusterStatus = garageAdminApi.getClusterStatus();
        var nodes = clusterStatus.nodes();

        if (nodes.isEmpty()) {
            return LayoutReconcileResult.noNodes(clusterStatus.layoutVersion());
        }

        // Only single-node clusters are assigned a layout: the Helm chart deploys replication_factor=1.
        if (nodes.size() > 1) {
            return LayoutReconcileResult.unsupportedTopology(
                    nodes.size(),
                    clusterStatus.layoutVersion()
            );
        }

        var node = nodes.getFirst();

        requireNotManagedElsewhere(garageCluster, node.id());

        if (!node.isUp()) {
            return LayoutReconcileResult.nodeDown(
                    node.id(),
                    clusterStatus.layoutVersion()
            );
        }

        var desiredRole = desiredRole(garageCluster.getSpec().getLayout());

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
                Quantities.toBytes(layoutSpec.getCapacity(), "spec.layout.capacity"),
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

    /// Two resources with different layouts would apply their roles in turn, a new layout version at every resync.
    /// The node id finds the same Garage even when the two `adminEndpoint` values are spelled differently.
    private void requireNotManagedElsewhere(
            GarageCluster garageCluster,
            String nodeId
    ) {
        var managedElsewhere = kubernetesClient.resources(GarageCluster.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .filter(other -> !Objects.equals(other.getMetadata().getUid(), garageCluster.getMetadata().getUid()))
                .filter(other -> other.getStatus() != null && nodeId.equals(other.getStatus().getNodeId()))
                .findFirst();

        if (managedElsewhere.isPresent()) {
            var other = managedElsewhere.get();

            throw new IllegalStateException(
                    "The Garage node is already managed by GarageCluster %s/%s [nodeId=%s]".formatted(
                            other.getMetadata().getNamespace(),
                            other.getMetadata().getName(),
                            nodeId
                    )
            );
        }
    }

    private LayoutReconcileResult applyRole(
            GarageAdminApi garageAdminApi,
            String nodeId,
            NodeAssignedRole desiredRole
    ) {
        // GetClusterLayout, not the cluster status, is authoritative for the version to apply.
        var currentLayout = garageAdminApi.getClusterLayout();

        log.info(
                "Staging Garage cluster layout role [nodeId={}, version={}, zone={}, capacity={}]",
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
                "Applied Garage cluster layout [nodeId={}, version={}]",
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
