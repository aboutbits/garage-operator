package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.dto.GetClusterStatusResponse;
import it.aboutbits.garage.core.adminapi.dto.NodeAssignedRole;
import it.aboutbits.garage.core.adminapi.dto.NodeResp;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        return new NodeResp(
                id,
                isUp,
                false,
                null,
                null,
                null
        );
    }

    @Nested
    class ReconcileLayout {
        private final GarageCluster garageCluster = garageCluster("uid-1", "acme");

        @BeforeEach
        void givenOnlyThisGarageCluster() {
            givenGarageClusters(garageCluster);
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
            when(garageAdminApi.getClusterStatus())
                    .thenReturn(new GetClusterStatusResponse(LAYOUT_VERSION, List.of(nodes)));
        }

        private void thenNoLayoutIsWritten() {
            verify(garageAdminApi, never()).updateClusterLayout(any());
            verify(garageAdminApi, never()).applyClusterLayout(any());
        }

        @Test
        @DisplayName("A cluster without nodes should wait, and keep the layout version")
        void noNodes_awaitsCluster() {
            // given
            givenNodes();

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.NO_NODES);
            assertThat(result.nodeId()).isNull();
            assertThat(result.layoutVersion()).isEqualTo(LAYOUT_VERSION);

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A cluster with more than one node should be refused, and keep the layout version")
        void twoNodes_isUnsupported() {
            // given
            givenNodes(
                    node("node-1", true),
                    node("node-2", true)
            );

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.UNSUPPORTED_TOPOLOGY);
            assertThat(result.layoutVersion()).isEqualTo(LAYOUT_VERSION);
            assertThat(result.message()).contains("2 nodes");

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A node that is not connected should wait")
        void nodeDown_awaitsCluster() {
            // given
            givenNodes(node("node-1", false));

            // when
            var result = garageClusterService.reconcileLayout(garageAdminApi, garageCluster);

            // then
            assertThat(result.outcome()).isEqualTo(LayoutReconcileResult.Outcome.NODE_DOWN);
            assertThat(result.nodeId()).isEqualTo("node-1");
            assertThat(result.layoutVersion()).isEqualTo(LAYOUT_VERSION);

            thenNoLayoutIsWritten();
        }

        @Test
        @DisplayName("A node that another GarageCluster manages should be refused")
        void managedElsewhere_isRefused() {
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
                    .hasMessageContaining("already managed by GarageCluster acme/other. Delete one of the two GarageClusters [nodeId=node-1]");

            thenNoLayoutIsWritten();
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
