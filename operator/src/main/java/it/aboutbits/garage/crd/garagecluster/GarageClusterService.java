package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage.core.Quantities;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.dto.ApplyClusterLayoutRequest;
import it.aboutbits.garage.core.adminapi.dto.LayoutNodeRole;
import it.aboutbits.garage.core.adminapi.dto.LayoutParameters;
import it.aboutbits.garage.core.adminapi.dto.NodeAssignedRole;
import it.aboutbits.garage.core.adminapi.dto.NodeResp;
import it.aboutbits.garage.core.adminapi.dto.NodeRoleChangeRequest;
import it.aboutbits.garage.core.adminapi.dto.UpdateClusterLayoutRequest;
import it.aboutbits.garage.core.adminapi.dto.ZoneRedundancy;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
@RequiredArgsConstructor
@Slf4j
@NullMarked
public class GarageClusterService {
    private final KubernetesClient kubernetesClient;

    /// Idempotent: when every role and parameter already matches, no write request is sent.
    public LayoutReconcileResult reconcileLayout(
            GarageAdminApi garageAdminApi,
            GarageCluster garageCluster
    ) {
        var spec = garageCluster.getSpec();
        var clusterStatus = garageAdminApi.getClusterStatus();
        var nodes = clusterStatus.nodes();

        requireNotManagedElsewhere(garageCluster, nodes);

        // A node that lost its metadata starts over under a new id, alone and at version 0. Behind a Service that
        // balances over all nodes it may be the one that answers, and must not be given a layout of its own.
        var recordedLayoutVersion = recordedLayoutVersion(garageCluster);

        if (clusterStatus.layoutVersion() < recordedLayoutVersion) {
            return LayoutReconcileResult.outdatedNode(
                    clusterStatus.layoutVersion(),
                    recordedLayoutVersion
            );
        }

        var nodeStatuses = nodeStatuses(nodes, currentRoles(nodes));
        var connectedNodes = nodes.stream()
                .filter(NodeResp::isUp)
                .count();
        var minNodes = Objects.requireNonNullElse(spec.getMinNodes(), 1);

        if (connectedNodes < minNodes) {
            return LayoutReconcileResult.awaitingNodes(
                    nodeStatuses,
                    connectedNodes,
                    minNodes,
                    clusterStatus.layoutVersion()
            );
        }

        // GetClusterLayout, not the cluster status, is authoritative for the version to apply.
        var currentLayout = garageAdminApi.getClusterLayout();

        // Behind a Service, this request may reach another node than the first, so it is held to the same guard.
        var knownLayoutVersion = Math.max(recordedLayoutVersion, clusterStatus.layoutVersion());

        if (currentLayout.version() < knownLayoutVersion) {
            return LayoutReconcileResult.outdatedNode(
                    currentLayout.version(),
                    knownLayoutVersion
            );
        }

        var roleChanges = roleChanges(
                nodes,
                desiredRole(spec.getLayout()),
                Set.copyOf(spec.getRemovedNodes())
        );
        var parameters = parametersChange(spec, currentLayout.parameters());

        if (roleChanges.isEmpty() && parameters == null) {
            return LayoutReconcileResult.upToDate(
                    nodeStatuses,
                    currentLayout.version()
            );
        }

        return applyLayout(
                garageAdminApi,
                nodes,
                nodeStatuses,
                currentLayout.version(),
                roleChanges,
                parameters
        );
    }

    NodeAssignedRole desiredRole(GarageClusterLayoutSpec layoutSpec) {
        return new NodeAssignedRole(
                layoutSpec.getZone(),
                Quantities.toBytes(layoutSpec.getCapacity(), "spec.layout.capacity"),
                List.copyOf(layoutSpec.getTags())
        );
    }

    /// A role assigned or removed by hand is reset, as long as the node is not listed in `spec.removedNodes`.
    List<NodeRoleChangeRequest> roleChanges(
            List<NodeResp> nodes,
            NodeAssignedRole desiredRole,
            Set<String> removedNodes
    ) {
        var roleChanges = new ArrayList<NodeRoleChangeRequest>();

        for (var node : nodes) {
            if (removedNodes.contains(node.id())) {
                if (node.role() != null) {
                    roleChanges.add(NodeRoleChangeRequest.remove(node.id()));
                }

                continue;
            }

            // Only a connected node joins: Garage still lists a node that is gone, such as one replaced under a new id,
            // and it would never take over its share of the data.
            if (node.role() == null && (!node.isUp() || node.draining())) {
                continue;
            }

            if (!rolesMatch(node.role(), desiredRole)) {
                roleChanges.add(NodeRoleChangeRequest.assign(node.id(), desiredRole));
            }
        }

        return roleChanges;
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

    @Nullable LayoutParameters parametersChange(
            GarageClusterSpec spec,
            LayoutParameters currentParameters
    ) {
        var zoneRedundancy = spec.getZoneRedundancy();

        if (zoneRedundancy == null) {
            return null;
        }

        var desiredParameters = new LayoutParameters(
                zoneRedundancy.getIntVal() != null
                        ? ZoneRedundancy.atLeastZones(zoneRedundancy.getIntVal())
                        : ZoneRedundancy.MAXIMUM
        );

        return desiredParameters.equals(currentParameters)
                ? null
                : desiredParameters;
    }

    private long recordedLayoutVersion(GarageCluster garageCluster) {
        var status = garageCluster.getStatus();

        //noinspection ConstantConditions
        return status != null
                ? status.getLayoutVersion()
                : 0;
    }

    /// Two resources with different layouts would apply their roles in turn, a new layout version at every resync.
    /// The node ids find the same Garage even when the two `adminEndpoint` values are spelled differently.
    private void requireNotManagedElsewhere(
            GarageCluster garageCluster,
            List<NodeResp> nodes
    ) {
        var nodeIds = nodes.stream()
                .map(NodeResp::id)
                .collect(Collectors.toSet());

        for (var other : kubernetesClient.resources(GarageCluster.class).inAnyNamespace().list().getItems()) {
            if (Objects.equals(other.getMetadata().getUid(), garageCluster.getMetadata().getUid())) {
                continue;
            }

            var sharedNodeId = claimedNodeIds(other)
                    .filter(nodeIds::contains)
                    .findFirst();

            if (sharedNodeId.isPresent()) {
                throw new IllegalStateException(
                        "The Garage cluster is already managed by GarageCluster %s/%s. Delete one of the two GarageClusters [nodeId=%s]".formatted(
                                other.getMetadata().getNamespace(),
                                other.getMetadata().getName(),
                                sharedNodeId.get()
                        )
                );
            }
        }
    }

    private Stream<String> claimedNodeIds(GarageCluster garageCluster) {
        var status = garageCluster.getStatus();

        //noinspection ConstantConditions
        if (status == null) {
            return Stream.empty();
        }

        return Stream.concat(
                status.getNodes().stream().map(GarageClusterNodeStatus::getId),
                Stream.ofNullable(status.getNodeId())
        );
    }

    private LayoutReconcileResult applyLayout(
            GarageAdminApi garageAdminApi,
            List<NodeResp> nodes,
            List<GarageClusterNodeStatus> nodeStatuses,
            long layoutVersion,
            List<NodeRoleChangeRequest> roleChanges,
            @Nullable LayoutParameters parameters
    ) {
        log.info(
                "Staging Garage cluster layout changes [layoutVersion={}, roleChanges={}, spec.zoneRedundancy={}]",
                layoutVersion,
                roleChanges.size(),
                parameters != null
                        ? parameters.zoneRedundancy().toJson()
                        : null
        );

        garageAdminApi.updateClusterLayout(
                new UpdateClusterLayoutRequest(roleChanges, parameters)
        );

        var preview = garageAdminApi.previewClusterLayoutChanges();

        if (preview.error() != null) {
            // Left staged, the changes would be applied with the next change, also one made by hand.
            garageAdminApi.revertClusterLayout();

            return LayoutReconcileResult.rejected(
                    nodeStatuses,
                    layoutVersion,
                    preview.error()
            );
        }

        // Garage requires the new version to be spelled out as a safety measure.
        var applied = garageAdminApi.applyClusterLayout(
                new ApplyClusterLayoutRequest(layoutVersion + 1)
        );

        var appliedLayout = applied.layout();

        var removedNodeIds = roleChanges.stream()
                .filter(roleChange -> Boolean.TRUE.equals(roleChange.remove()))
                .map(NodeRoleChangeRequest::id)
                .collect(Collectors.toSet());

        // Read before the apply, the nodes do not show yet that a removed node now hands its data over.
        var appliedNodeStatuses = nodeStatuses(nodes, appliedRoles(appliedLayout.roles()));

        appliedNodeStatuses.stream()
                .filter(nodeStatus -> removedNodeIds.contains(nodeStatus.getId()))
                .forEach(nodeStatus -> nodeStatus.setDraining(true));

        log.info(
                "Applied Garage cluster layout [layoutVersion={}, roles={}]",
                appliedLayout.version(),
                appliedLayout.roles().size()
        );

        return LayoutReconcileResult.applied(
                appliedNodeStatuses,
                appliedLayout.version()
        );
    }

    private Map<String, NodeAssignedRole> currentRoles(List<NodeResp> nodes) {
        var roles = new HashMap<String, NodeAssignedRole>();

        for (var node : nodes) {
            if (node.role() != null) {
                roles.put(node.id(), node.role());
            }
        }

        return roles;
    }

    private Map<String, NodeAssignedRole> appliedRoles(List<LayoutNodeRole> roles) {
        return roles.stream()
                .collect(Collectors.toMap(
                        LayoutNodeRole::id,
                        role -> new NodeAssignedRole(role.zone(), role.capacity(), role.tags()),
                        (first, second) -> first
                ));
    }

    private List<GarageClusterNodeStatus> nodeStatuses(
            List<NodeResp> nodes,
            Map<String, NodeAssignedRole> roles
    ) {
        return nodes.stream()
                .map(node -> {
                    var role = roles.get(node.id());

                    return new GarageClusterNodeStatus()
                            .setId(node.id())
                            .setHostname(node.hostname())
                            .setUp(node.isUp())
                            .setDraining(node.draining())
                            .setZone(role != null ? role.zone() : null)
                            .setCapacity(role != null ? role.capacity() : null);
                })
                .toList();
    }
}
