package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import it.aboutbits.garage._support.testdata.base.TestUtil;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
import it.aboutbits.garage.core.adminapi.dto.LayoutNodeRole;
import it.aboutbits.garage.core.adminapi.dto.NodeResp;
import it.aboutbits.garage.core.adminapi.dto.ZoneRedundancy;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static it.aboutbits.garage._support.testdata.base.TestUtil.AWAIT_SECONDS;
import static it.aboutbits.garage._support.testdata.base.TestUtil.awaitPhase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Exercises the reconciler against the three-node Garage of the Compose Dev Service, which is separate from the
/// single node the other tests use.
///
/// Connecting the nodes is the job of whatever deploys Garage, so the test does it before the first test, through the
/// Admin API. Like the single node, the cluster is shared by every test and its layout cannot be rolled back, so the
/// assertions are about relative changes.
///
/// The test that removes a node runs last: Garage may take minutes to let the removed node finish its handover, and
/// until then the node gets no role again.
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@RequiredArgsConstructor
@NullMarked
class GarageClusterMultiNodeTest {
    private static final int NODE_COUNT = 3;

    private final KubernetesClient kubernetesClient;

    private final Given given;

    private final GarageAdminClientFactory garageAdminClientFactory;

    @ConfigProperty(name = "garage.cluster.node-1.admin.port")
    private final int node1AdminPort;

    @ConfigProperty(name = "garage.cluster.node-2.admin.port")
    private final int node2AdminPort;

    @ConfigProperty(name = "garage.cluster.node-3.admin.port")
    private final int node3AdminPort;

    @ConfigProperty(name = "garage.admin.token")
    private final String garageAdminToken;

    private final List<GarageAdminApi> garageAdminApis = new ArrayList<>();

    private List<Integer> adminPorts() {
        return List.of(node1AdminPort, node2AdminPort, node3AdminPort);
    }

    private String adminEndpoint(int node) {
        return "http://localhost:%d".formatted(adminPorts().get(node - 1));
    }

    private GarageAdminApi garageAdminApi() {
        return garageAdminApis.getFirst();
    }

    @BeforeAll
    void connectNodes() throws IOException, InterruptedException {
        for (var adminPort : adminPorts()) {
            garageAdminApis.add(garageAdminClientFactory.create(
                    URI.create("http://localhost:%d".formatted(adminPort)),
                    garageAdminToken
            ));
        }

        var nodeIds = new ArrayList<String>();

        for (var adminApi : garageAdminApis) {
            var nodeId = await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .ignoreExceptions()
                    .until(() -> adminApi.getClusterStatus().nodes().getFirst().id(), id -> !id.isBlank());

            nodeIds.add(nodeId);
        }

        // Nodes 2 and 3 are enough: Garage passes the peers on, so that every node ends up connected to every other.
        var peers = List.of(
                "\"%s@garage-cluster-node-2:3901\"".formatted(nodeIds.get(1)),
                "\"%s@garage-cluster-node-3:3901\"".formatted(nodeIds.get(2))
        );

        try (var httpClient = HttpClient.newHttpClient()) {
            var response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(adminEndpoint(1) + "/v2/ConnectClusterNodes"))
                            .header("Authorization", "Bearer " + garageAdminToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("[%s]".formatted(String.join(",", peers))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).doesNotContain("\"success\":false");
        }
    }

    @AfterAll
    void closeGarageAdminApis() throws IOException {
        for (var adminApi : garageAdminApis) {
            if (adminApi instanceof Closeable closeable) {
                closeable.close();
            }
        }
    }

    @BeforeEach
    void setUp() {
        for (var adminApi : garageAdminApis) {
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        var nodes = adminApi.getClusterStatus().nodes();

                        return nodes.size() == NODE_COUNT && nodes.stream().allMatch(NodeResp::isUp);
                    });
        }

        TestUtil.resetEnvironment(kubernetesClient);
    }

    private List<String> nodeIds() {
        return garageAdminApi().getClusterStatus()
                .nodes()
                .stream()
                .map(NodeResp::id)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("A GarageCluster should give every node the role, in one layout version")
    void garageCluster_assignsEveryNode() {
        // given
        var layoutVersion = garageAdminApi().getClusterLayout().version();

        // when
        var garageCluster = given.one()
                .garageCluster()
                .withAdminEndpoint(adminEndpoint(1))
                .withMinNodes(NODE_COUNT)
                // Differs from every other test, so that the layout has to change.
                .withCapacity("11Gi")
                .returnFirst();

        // then
        var status = awaitPhase(kubernetesClient, garageCluster, CRPhase.READY).getStatus();

        assertThat(status.getLayoutVersion()).isEqualTo(layoutVersion + 1);
        assertThat(status.getStorageNodes()).isEqualTo(NODE_COUNT);
        assertThat(status.getNodes())
                .extracting(GarageClusterNodeStatus::getId)
                .containsExactlyInAnyOrderElementsOf(nodeIds());
        assertThat(status.getNodes())
                .allSatisfy(node -> assertThat(node.getCapacity()).isEqualTo(11811160064L));

        var roles = garageAdminApi().getClusterLayout().roles();

        assertThat(roles)
                .extracting(LayoutNodeRole::id)
                .containsExactlyInAnyOrderElementsOf(nodeIds());
        assertThat(roles)
                .allSatisfy(role -> assertThat(role.capacity()).isEqualTo(11811160064L));
    }

    @Test
    @DisplayName("Fewer connected nodes than spec.minNodes should wait, and leave the layout alone")
    void tooFewNodes_awaitsNodes() {
        // given
        var layoutVersion = garageAdminApi().getClusterLayout().version();

        // when
        var garageCluster = given.one()
                .garageCluster()
                .withAdminEndpoint(adminEndpoint(1))
                .withMinNodes(NODE_COUNT + 1)
                .withCapacity("12Gi")
                .returnFirst();

        // then
        var status = awaitPhase(kubernetesClient, garageCluster, CRPhase.PENDING).getStatus();

        assertThat(status.getMessage()).contains("[connectedNodes=3, spec.minNodes=4]");
        assertThat(status.getNodes()).hasSize(NODE_COUNT);
        assertThat(garageAdminApi().getClusterLayout().version()).isEqualTo(layoutVersion);
    }

    @Test
    @DisplayName("A layout that Garage rejects should surface as an error, and leave nothing staged")
    void rejectedLayout_setsErrorPhase() {
        // given
        var layoutVersion = garageAdminApi().getClusterLayout().version();

        // when
        // All nodes share one zone, so no partition can be spread over two.
        var garageCluster = given.one()
                .garageCluster()
                .withAdminEndpoint(adminEndpoint(1))
                .withZoneRedundancy(new IntOrString(2))
                .returnFirst();

        // then
        var status = awaitPhase(kubernetesClient, garageCluster, CRPhase.ERROR).getStatus();

        assertThat(status.getMessage())
                .startsWith("Garage rejected the cluster layout, nothing was applied [error=")
                .contains("redundancy");

        var layout = garageAdminApi().getClusterLayout();

        assertThat(layout.version()).isEqualTo(layoutVersion);
        assertThat(layout.stagedRoleChanges()).isEmpty();
        assertThat(layout.stagedParameters()).isNull();
    }

    @Test
    @DisplayName("spec.zoneRedundancy should be applied as a layout parameter")
    void zoneRedundancy_isApplied() {
        // given
        var garageCluster = given.one()
                .garageCluster()
                .withAdminEndpoint(adminEndpoint(1))
                .withZoneRedundancy(new IntOrString("maximum"))
                .returnFirst();

        awaitPhase(kubernetesClient, garageCluster, CRPhase.READY);

        // when
        var edited = kubernetesClient.resources(GarageCluster.class)
                .inNamespace(garageCluster.getMetadata().getNamespace())
                .withName(garageCluster.getMetadata().getName())
                .edit(item -> {
                    item.getSpec().setZoneRedundancy(new IntOrString(1));
                    return item;
                });

        // then
        awaitPhase(kubernetesClient, edited, CRPhase.READY);

        assertThat(garageAdminApi().getClusterLayout().parameters().zoneRedundancy())
                .isEqualTo(ZoneRedundancy.atLeastZones(1));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    @DisplayName("A node in spec.removedNodes should lose its role, and show as draining until its handover is done")
    void removedNode_losesRole() {
        // given
        var garageCluster = given.one()
                .garageCluster()
                .withAdminEndpoint(adminEndpoint(1))
                .withMinNodes(NODE_COUNT)
                .returnFirst();

        // precondition: the node to remove has a role
        assertThat(awaitPhase(kubernetesClient, garageCluster, CRPhase.READY).getStatus().getNodes())
                .allSatisfy(node -> assertThat(node.getZone()).isNotNull());

        var removedNodeId = nodeIds().getLast();

        // when
        var edited = kubernetesClient.resources(GarageCluster.class)
                .inNamespace(garageCluster.getMetadata().getNamespace())
                .withName(garageCluster.getMetadata().getName())
                .edit(item -> {
                    item.getSpec().setRemovedNodes(List.of(removedNodeId));
                    return item;
                });

        // then
        var status = awaitPhase(kubernetesClient, edited, CRPhase.READY).getStatus();

        var nodesWithRole = status.getNodes()
                .stream()
                .filter(node -> node.getZone() != null)
                .map(GarageClusterNodeStatus::getId)
                .collect(Collectors.toSet());

        assertThat(nodesWithRole).hasSize(NODE_COUNT - 1).doesNotContain(removedNodeId);
        assertThat(garageAdminApi().getClusterLayout().roles())
                .extracting(LayoutNodeRole::id)
                .doesNotContain(removedNodeId);
        assertThat(removedNodeStatus(status, removedNodeId).isDraining()).isTrue();

        // How long the handover takes is up to Garage, but the status follows it long before the resync.
        var lastProbeTime = Objects.requireNonNull(status.getLastProbeTime());

        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    var probeTime = kubernetesClient.resource(edited).get().getStatus().getLastProbeTime();

                    return probeTime != null && probeTime.isAfter(lastProbeTime);
                });
    }

    private static GarageClusterNodeStatus removedNodeStatus(
            GarageClusterStatus status,
            String removedNodeId
    ) {
        return status.getNodes()
                .stream()
                .filter(node -> node.getId().equals(removedNodeId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("A second GarageCluster for another node of the same Garage should surface as an error")
    void secondGarageClusterForOtherNode_setsErrorPhase() {
        // given
        var first = given.one()
                .garageCluster()
                .withAdminEndpoint(adminEndpoint(1))
                .returnFirst();

        awaitPhase(kubernetesClient, first, CRPhase.READY);

        // when
        var second = given.one()
                .garageCluster()
                .withAdminEndpoint(adminEndpoint(2))
                .returnFirst();

        // then
        var status = awaitPhase(kubernetesClient, second, CRPhase.ERROR).getStatus();

        assertThat(status.getMessage()).contains(
                "already managed by GarageCluster %s/%s".formatted(
                        first.getMetadata().getNamespace(),
                        first.getMetadata().getName()
                )
        );
    }
}
