package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.dto.ApplyClusterLayoutResponse;
import it.aboutbits.garage.core.adminapi.dto.GetClusterLayoutResponse;
import it.aboutbits.garage.core.adminapi.dto.GetClusterStatusResponse;
import it.aboutbits.garage.core.adminapi.dto.LayoutNodeRole;
import it.aboutbits.garage.core.adminapi.dto.LayoutParameters;
import it.aboutbits.garage.core.adminapi.dto.NodeAssignedRole;
import it.aboutbits.garage.core.adminapi.dto.NodeResp;
import it.aboutbits.garage.core.adminapi.dto.NodeRoleChangeRequest;
import it.aboutbits.garage.core.adminapi.dto.PreviewClusterLayoutChangesResponse;
import it.aboutbits.garage.core.adminapi.dto.UpdateClusterLayoutRequest;
import it.aboutbits.garage.core.adminapi.dto.ZoneRedundancy;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@NullMarked
class GarageClusterServiceTest {
    private static final long LAYOUT_VERSION = 5;

    private static final NodeAssignedRole DESIRED_ROLE = new NodeAssignedRole(
            "default",
            21474836480L,
            List.of()
    );

    private final KubernetesClient kubernetesClient = mock(KubernetesClient.class);
    private final GarageAdminApi garageAdminApi = mock(GarageAdminApi.class);

    private final GarageClusterService garageClusterService = new GarageClusterService(kubernetesClient);

    private static GarageCluster garageCluster(
            String uid,
            String name
    ) {
        var garageCluster = new GarageCluster();

        garageCluster.setMetadata(new ObjectMetaBuilder()
                .withNamespace("acme")
                .withName(name)
                .withUid(uid)
                .build()
        );

        var layout = new GarageClusterLayoutSpec();

        layout.setZone("default");
        layout.setCapacity("20Gi");

        var spec = new GarageClusterSpec();

        spec.setLayout(layout);

        garageCluster.setSpec(spec);

        return garageCluster;
    }

    private static NodeResp node(
            String id,
            boolean isUp
    ) {
        return node(id, isUp, false, null);
    }

    private static NodeResp node(
            String id,
            boolean isUp,
            boolean draining,
            @Nullable NodeAssignedRole role
    ) {
        return new NodeResp(
                id,
                isUp,
                draining,
                null,
                null,
                role
        );
    }

    private static GetClusterLayoutResponse layout(
            long version,
            List<LayoutNodeRole> roles,
            ZoneRedundancy zoneRedundancy
    ) {
        return new GetClusterLayoutResponse(
                version,
                roles,
                new LayoutParameters(zoneRedundancy),
                List.of(),
                null
        );
    }

    @Nested
    class ReconcileLayout {
        private final GarageCluster garageCluster = garageCluster("uid-1", "acme");

        @BeforeEach
        void givenOnlyThisGarageCluster() {
            givenGarageClusters(garageCluster);

            when(garageAdminApi.getClusterLayout())
                    .thenReturn(layout(LAYOUT_VERSION, List.of(), ZoneRedundancy.MAXIMUM));
        }

        @SuppressWarnings("unchecked")
        private void givenGarageClusters(GarageCluster... garageClusters) {
            MixedOperation<GarageCluster, KubernetesResourceList<GarageCluster>, Resource<GarageCluster>> operation =
                    mock(MixedOperation.class);
            AnyNamespaceOperation<GarageCluster, KubernetesResourceList<GarageCluster>, Resource<GarageCluster>> anyNamespaceOperation =
                    mock(AnyNamespaceOperation.class);
            KubernetesResourceList<GarageCluster> list = mock(KubernetesResourceList.class);

            when(kubernetesClient.resources(GarageCluster.class)).thenReturn(operation);
            when(operation.inAnyNamespace()).thenReturn(anyNamespaceOperation);
            when(anyNamespaceOperation.list()).thenReturn(list);
            when(list.getItems()).thenReturn(List.of(garageClusters));
        }

        private void givenNodes(NodeResp... nodes) {
            givenNodes(LAYOUT_VERSION, nodes);
        }

        private void givenNodes(
                long layoutVersion,
                NodeResp... nodes
        ) {
            when(garageAdminApi.getClusterStatus())
                    .thenReturn(new GetClusterStatusResponse(layoutVersion, List.of(nodes)));
        }

        private void givenPreview(@Nullable String error) {
            when(garageAdminApi.previewClusterLayoutChanges())
                    .thenReturn(new PreviewClusterLayoutChangesResponse(error));
        }

        private void givenApply(LayoutNodeRole... roles) {
            when(garageAdminApi.applyClusterLayout(any()))
                    .thenReturn(new ApplyClusterLayoutResponse(
                            List.of(),
                            layout(LAYOUT_VERSION + 1, List.of(roles), ZoneRedundancy.MAXIMUM)
                    ));
        }

        private UpdateClusterLayoutRequest thenStaged() {
            var request = ArgumentCaptor.forClass(UpdateClusterLayoutRequest.class);

            verify(garageAdminApi).updateClusterLayout(request.capture());

            return request.getValue();
        }

        private void thenNoLayoutIsWritten() {
            verify(garageAdminApi, never()).updateClusterLayout(any());
            verify(garageAdminApi, never()).applyClusterLayout(any());
        }

        @Test
        @DisplayName("A cluster without nodes should wait, and keep the layout version")
        void noNodes_awaitsNodes() {
            // given
            givenNodes();

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.AWAITING_NODES);
            assertThat(result.nodes()).isEmpty();
            assertThat(result.layoutVersion()).isEqualTo(LAYOUT_VERSION);

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A node that is not connected should wait")
        void nodeDown_awaitsNodes() {
            // given
            givenNodes(node("node-1", false));

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.AWAITING_NODES);
            assertThat(result.nodes()).extracting(GarageClusterNodeStatus::getId).containsExactly("node-1");

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("Fewer connected nodes than spec.minNodes should wait")
        void tooFewNodes_awaitsNodes() {
            // given
            garageCluster.getSpec().setMinNodes(3);

            givenNodes(
                    node("node-1", true),
                    node("node-2", true),
                    node("node-3", false)
            );

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.AWAITING_NODES);
            assertThat(result.message()).contains("[connectedNodes=2, spec.minNodes=3]");

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A node that reports an older layout than the recorded one should wait, and keep the recorded version")
        void olderLayoutVersion_awaitsConnectedNode() {
            // given
            garageCluster.setStatus(new GarageClusterStatus().setLayoutVersion(LAYOUT_VERSION));

            givenNodes(0, node("node-new", true));

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.OUTDATED_NODE);
            assertThat(result.nodes()).isEmpty();
            assertThat(result.layoutVersion()).isEqualTo(LAYOUT_VERSION);

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A layout read from a node behind the one that reported the status should wait, and write nothing")
        void olderClusterLayout_awaitsConnectedNode() {
            // given
            givenNodes(node("node-1", true, false, DESIRED_ROLE));

            when(garageAdminApi.getClusterLayout())
                    .thenReturn(layout(0, List.of(), ZoneRedundancy.MAXIMUM));

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.OUTDATED_NODE);
            assertThat(result.nodes()).isEmpty();
            assertThat(result.layoutVersion()).isEqualTo(LAYOUT_VERSION);

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A node removed by the applied layout should show as draining")
        void removedNode_isDraining() {
            // given
            garageCluster.getSpec().setRemovedNodes(List.of("node-2"));

            givenNodes(
                    node("node-1", true, false, DESIRED_ROLE),
                    node("node-2", true, false, DESIRED_ROLE)
            );
            givenPreview(null);
            givenApply(new LayoutNodeRole("node-1", "default", 21474836480L, List.of(), null, null));

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.APPLIED);
            assertThat(result.nodes())
                    .filteredOn(node -> node.getId().equals("node-2"))
                    .singleElement()
                    .satisfies(node -> {
                        assertThat(node.isDraining()).isTrue();
                        assertThat(node.getZone()).isNull();
                    });
            assertThat(result.nodes())
                    .filteredOn(node -> node.getId().equals("node-1"))
                    .singleElement()
                    .satisfies(node -> assertThat(node.isDraining()).isFalse());
        }

        @Test
        @DisplayName("Every connected node should be staged in one layout version, after a preview")
        void connectedNodes_areAppliedTogether() {
            // given
            givenNodes(
                    node("node-1", true),
                    node("node-2", true)
            );
            givenPreview(null);
            givenApply(
                    new LayoutNodeRole("node-1", "default", 21474836480L, List.of(), null, null),
                    new LayoutNodeRole("node-2", "default", 21474836480L, List.of(), null, null)
            );

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.APPLIED);
            assertThat(result.layoutVersion()).isEqualTo(LAYOUT_VERSION + 1);
            assertThat(result.nodes())
                    .allSatisfy(node -> assertThat(node.getCapacity()).isEqualTo(21474836480L));

            assertThat(thenStaged().roles())
                    .extracting(NodeRoleChangeRequest::id)
                    .containsExactly("node-1", "node-2");

            verify(garageAdminApi).previewClusterLayoutChanges();
        }

        @Test
        @DisplayName("A layout that Garage rejects in the preview should be reverted, not applied")
        void rejectedPreview_isReverted() {
            // given
            givenNodes(node("node-1", true));
            givenPreview("The number of nodes with positive capacity (1) is smaller than the replication factor (3).");

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.REJECTED);
            assertThat(result.layoutVersion()).isEqualTo(LAYOUT_VERSION);
            assertThat(result.message()).contains("[error=The number of nodes with positive capacity (1)");

            verify(garageAdminApi).revertClusterLayout();
            verify(garageAdminApi, never()).applyClusterLayout(any());
        }

        @Test
        @DisplayName("Matching roles and parameters should write nothing")
        void matchingLayout_isUpToDate() {
            // given
            garageCluster.getSpec().setZoneRedundancy(new IntOrString("maximum"));

            givenNodes(node("node-1", true, false, DESIRED_ROLE));

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.UP_TO_DATE);
            assertThat(result.nodes())
                    .singleElement()
                    .satisfies(node -> assertThat(node.getZone()).isEqualTo("default"));

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A different zone redundancy should be staged as a parameter")
        void differentZoneRedundancy_isStaged() {
            // given
            garageCluster.getSpec().setZoneRedundancy(new IntOrString(2));

            givenNodes(node("node-1", true, false, DESIRED_ROLE));
            givenPreview(null);
            givenApply();

            // when
            garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            var staged = thenStaged();

            assertThat(staged.roles()).isEmpty();
            assertThat(staged.parameters()).isEqualTo(new LayoutParameters(ZoneRedundancy.atLeastZones(2)));
        }

        @Test
        @DisplayName("A node that another GarageCluster manages should be refused")
        void managedElsewhere_isRefused() {
            // given
            givenNodes(
                    node("node-1", true),
                    node("node-2", true)
            );

            var other = garageCluster("uid-2", "other");

            other.setStatus(new GarageClusterStatus().setNodes(List.of(new GarageClusterNodeStatus().setId("node-2"))));

            givenGarageClusters(garageCluster, other);

            // when
            var thrown = catchThrowable(() -> garageClusterService.reconcileLayout(garageAdminApi, garageCluster));

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already managed by GarageCluster acme/other. Delete one of the two GarageClusters [nodeId=node-2]");

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A node that a GarageCluster from before status.nodes manages should be refused")
        void managedElsewhereByNodeId_isRefused() {
            // given
            givenNodes(node("node-1", true));

            var other = garageCluster("uid-2", "other");

            other.setStatus(new GarageClusterStatus().setNodeId("node-1"));

            givenGarageClusters(garageCluster, other);

            // when
            var thrown = catchThrowable(() -> garageClusterService.reconcileLayout(garageAdminApi, garageCluster));

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[nodeId=node-1]");

            thenNoLayoutIsWritten();
        }
    }

    @Nested
    class RoleChanges {
        @Test
        @DisplayName("A connected node without a role should get one")
        void connectedNodeWithoutRole_isAssigned() {
            // when
            var roleChanges = garageClusterService.roleChanges(
                    List.of(node("node-1", true)),
                    DESIRED_ROLE,
                    Set.of()
            );

            // then
            assertThat(roleChanges).containsExactly(NodeRoleChangeRequest.assign("node-1", DESIRED_ROLE));
        }

        @Test
        @DisplayName("A node without a role that is down or draining should not get one")
        void absentNodeWithoutRole_isSkipped() {
            // when
            var roleChanges = garageClusterService.roleChanges(
                    List.of(
                            node("node-down", false),
                            node("node-draining", true, true, null)
                    ),
                    DESIRED_ROLE,
                    Set.of()
            );

            // then
            assertThat(roleChanges).isEmpty();
        }

        @Test
        @DisplayName("A node that is down should keep its role, and still get a changed one")
        void downNodeWithRole_isUpdated() {
            // given
            var oldRole = new NodeAssignedRole("default", 1073741824L, List.of());

            // when
            var roleChanges = garageClusterService.roleChanges(
                    List.of(
                            node("node-same", false, false, DESIRED_ROLE),
                            node("node-old", false, false, oldRole)
                    ),
                    DESIRED_ROLE,
                    Set.of()
            );

            // then
            assertThat(roleChanges).containsExactly(NodeRoleChangeRequest.assign("node-old", DESIRED_ROLE));
        }

        @Test
        @DisplayName("A node in spec.removedNodes should lose its role, and not get one back")
        void removedNodes_areRemoved() {
            // when
            var roleChanges = garageClusterService.roleChanges(
                    List.of(
                            node("node-with-role", false, false, DESIRED_ROLE),
                            node("node-without-role", true)
                    ),
                    DESIRED_ROLE,
                    Set.of("node-with-role", "node-without-role")
            );

            // then
            assertThat(roleChanges).containsExactly(NodeRoleChangeRequest.remove("node-with-role"));
        }
    }

    @Nested
    class DesiredRole {
        @Test
        @DisplayName("The spec should be translated into the role Garage records")
        void spec_isTranslatedToRole() {
            // given
            var layoutSpec = new GarageClusterLayoutSpec();

            layoutSpec.setZone("dc1");
            layoutSpec.setCapacity("20Gi");
            layoutSpec.setTags(List.of("ssd"));

            // when
            var role = garageClusterService.desiredRole(layoutSpec);

            // then
            assertThat(role.zone()).isEqualTo("dc1");
            assertThat(role.capacity()).isEqualTo(21474836480L);
            assertThat(role.tags()).containsExactly("ssd");
        }

        @Test
        @DisplayName("Tags set to an explicit null should mean no tags")
        void nullTags_meanNoTags() {
            // given
            var layoutSpec = new GarageClusterLayoutSpec();

            layoutSpec.setZone("dc1");
            layoutSpec.setCapacity("20Gi");
            layoutSpec.setTags(null);

            // when
            var role = garageClusterService.desiredRole(layoutSpec);

            // then
            assertThat(role.tags()).isEmpty();
        }
    }

    @Nested
    class ParametersChange {
        private final GarageClusterSpec spec = garageCluster("uid-1", "acme").getSpec();

        @Test
        @DisplayName("No spec.zoneRedundancy should leave the parameters alone")
        void noZoneRedundancy_isNoChange() {
            // when
            var parameters = garageClusterService.parametersChange(
                    spec,
                    new LayoutParameters(ZoneRedundancy.atLeastZones(2))
            );

            // then
            assertThat(parameters).isNull();
        }

        @Test
        @DisplayName("'maximum' should be translated, and match the same parameter")
        void maximum_matches() {
            // given
            spec.setZoneRedundancy(new IntOrString("maximum"));

            // when
            var parameters = garageClusterService.parametersChange(
                    spec,
                    new LayoutParameters(ZoneRedundancy.MAXIMUM)
            );

            // then
            assertThat(parameters).isNull();
        }

        @Test
        @DisplayName("A number of zones should be translated into atLeast")
        void numberOfZones_isAtLeast() {
            // given
            spec.setZoneRedundancy(new IntOrString(2));

            // when
            var parameters = garageClusterService.parametersChange(
                    spec,
                    new LayoutParameters(ZoneRedundancy.MAXIMUM)
            );

            // then
            assertThat(parameters).isEqualTo(new LayoutParameters(ZoneRedundancy.atLeastZones(2)));
        }
    }

    @Nested
    class RolesMatch {
        private final NodeAssignedRole desiredRole = new NodeAssignedRole(
                "default",
                21474836480L,
                List.of("ssd", "fast")
        );

        @Test
        @DisplayName("A node without a role should never match")
        void noRole_doesNotMatch() {
            // when
            var matches = garageClusterService.rolesMatch(null, desiredRole);

            // then
            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("An identical role should match")
        void identicalRole_matches() {
            // given
            var currentRole = new NodeAssignedRole(
                    "default",
                    21474836480L,
                    List.of("ssd", "fast")
            );

            // when
            var matches = garageClusterService.rolesMatch(currentRole, desiredRole);

            // then
            assertThat(matches).isTrue();
        }

        @Test
        @DisplayName("Tag order should not count as a difference")
        void reorderedTags_match() {
            // given
            var currentRole = new NodeAssignedRole(
                    "default",
                    21474836480L,
                    List.of("fast", "ssd")
            );

            // when
            var matches = garageClusterService.rolesMatch(currentRole, desiredRole);

            // then
            assertThat(matches).isTrue();
        }

        @Test
        @DisplayName("A different zone should count as a difference")
        void differentZone_doesNotMatch() {
            // given
            var currentRole = new NodeAssignedRole(
                    "dc2",
                    21474836480L,
                    List.of("ssd", "fast")
            );

            // when
            var matches = garageClusterService.rolesMatch(currentRole, desiredRole);

            // then
            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("A different capacity should count as a difference")
        void differentCapacity_doesNotMatch() {
            // given
            var currentRole = new NodeAssignedRole(
                    "default",
                    1073741824L,
                    List.of("ssd", "fast")
            );

            // when
            var matches = garageClusterService.rolesMatch(currentRole, desiredRole);

            // then
            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("A gateway node should not match a node with a capacity")
        void gatewayNode_doesNotMatch() {
            // given
            var currentRole = new NodeAssignedRole(
                    "default",
                    null,
                    List.of("ssd", "fast")
            );

            // when
            var matches = garageClusterService.rolesMatch(currentRole, desiredRole);

            // then
            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("Different tags should count as a difference")
        void differentTags_doNotMatch() {
            // given
            var currentRole = new NodeAssignedRole(
                    "default",
                    21474836480L,
                    List.of("ssd")
            );

            // when
            var matches = garageClusterService.rolesMatch(currentRole, desiredRole);

            // then
            assertThat(matches).isFalse();
        }
    }
}
