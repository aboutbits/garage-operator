package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import it.aboutbits.garage._support.testdata.base.TestUtil;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Exercises the reconciler against a real, unconfigured Garage node provided by the Compose Dev
/// Service.
///
/// The Garage container is shared by every test in this class and, unlike the Kubernetes
/// resources, its layout cannot be rolled back between tests. Assertions are therefore written
/// against *relative* changes — did the layout version advance, does the node's role now match the
/// spec — rather than against absolute version numbers.
@QuarkusTest
@NullMarked
class GarageClusterReconcilerTest {
    private static final long AWAIT_SECONDS = 60;

    @Inject
    @SuppressWarnings("NullAway.Init")
    KubernetesClient kubernetesClient;

    @Inject
    @SuppressWarnings("NullAway.Init")
    Given given;

    @Inject
    @SuppressWarnings("NullAway.Init")
    GarageAdminClientFactory garageAdminClientFactory;

    @ConfigProperty(name = "garage.admin.port")
    @SuppressWarnings("NullAway.Init")
    Integer garageAdminPort;

    @ConfigProperty(name = "garage.admin.token")
    @SuppressWarnings("NullAway.Init")
    String garageAdminToken;

    private GarageAdminApi garageAdminApi() {
        return garageAdminClientFactory.create(
                URI.create("http://localhost:%d".formatted(garageAdminPort)),
                garageAdminToken
        );
    }

    @BeforeEach
    void setUp() {
        // The Compose Dev Service starts the container, but Garage needs a moment before the
        // Admin API answers.
        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .ignoreExceptions()
                .until(() -> garageAdminApi().getClusterStatus().nodes().size() == 1);

        TestUtil.resetEnvironment(kubernetesClient);
    }

    private GarageCluster awaitPhase(
            GarageCluster garageCluster,
            CRPhase expectedPhase
    ) {
        //noinspection ConstantConditions
        return kubernetesClient.resources(GarageCluster.class)
                .inNamespace(garageCluster.getMetadata().getNamespace())
                .withName(garageCluster.getMetadata().getName())
                .waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getPhase() == expectedPhase,
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

    @Nested
    class Bootstrap {
        @Test
        @DisplayName("A GarageCluster should assign and apply a layout for the node")
        void garageCluster_appliesLayout() {
            var garageCluster = given.one()
                    .garageCluster()
                    .withZone("default")
                    .withCapacity("20Gi")
                    .returnFirst();

            var reconciled = awaitPhase(garageCluster, CRPhase.READY);

            var status = reconciled.getStatus();

            assertThat(status.getLayoutVersion()).isPositive();
            assertThat(status.getNodeId()).isNotBlank();
            assertThat(status.getHealth()).isNotBlank();
            assertThat(status.getStorageNodes()).isEqualTo(1);

            // Verify against Garage itself rather than trusting the status we just wrote.
            var node = garageAdminApi().getClusterStatus().nodes().getFirst();

            assertThat(node.id()).isEqualTo(status.getNodeId());
            assertThat(node.role()).isNotNull();

            var role = Objects.requireNonNull(node.role());

            assertThat(role.zone()).isEqualTo("default");
            assertThat(role.capacity()).isEqualTo(21474836480L);
        }

        @Test
        @DisplayName("A GarageCluster whose layout already matches should not create a new layout version")
        void matchingLayout_isNotReapplied() {
            var first = given.one()
                    .garageCluster()
                    .withZone("default")
                    .withCapacity("20Gi")
                    .returnFirst();

            awaitPhase(first, CRPhase.READY);

            var layoutVersion = garageAdminApi().getClusterLayout().version();

            // A second resource describing the same desired state must be a no-op against Garage.
            var second = given.one()
                    .garageCluster()
                    .withZone("default")
                    .withCapacity("20Gi")
                    .returnFirst();

            var reconciled = awaitPhase(second, CRPhase.READY);

            assertThat(reconciled.getStatus().getLayoutVersion()).isEqualTo(layoutVersion);
            assertThat(garageAdminApi().getClusterLayout().version()).isEqualTo(layoutVersion);
        }

        @Test
        @DisplayName("Changing the capacity should apply a new layout version")
        void changedCapacity_appliesNewLayoutVersion() {
            var garageCluster = given.one()
                    .garageCluster()
                    .withZone("default")
                    .withCapacity("20Gi")
                    .returnFirst();

            awaitPhase(garageCluster, CRPhase.READY);

            var layoutVersion = garageAdminApi().getClusterLayout().version();

            var changed = given.one()
                    .garageCluster()
                    .withZone("default")
                    .withCapacity("30Gi")
                    .returnFirst();

            var reconciled = awaitPhase(changed, CRPhase.READY);

            assertThat(reconciled.getStatus().getLayoutVersion()).isGreaterThan(layoutVersion);

            var node = garageAdminApi().getClusterStatus().nodes().getFirst();

            assertThat(node.role()).isNotNull();

            var role = Objects.requireNonNull(node.role());

            assertThat(role.capacity()).isEqualTo(32212254720L);
        }
    }

    @Nested
    class Failures {
        @Test
        @DisplayName("An invalid admin token should surface as an error")
        void invalidToken_setsErrorPhase() {
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withToken("definitely-not-the-admin-token")
                    .returnFirst();

            var garageCluster = given.one()
                    .garageCluster()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            var reconciled = awaitPhase(garageCluster, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).isNotBlank();
        }

        @Test
        @DisplayName("A missing admin Secret should surface as an error")
        void missingSecret_setsErrorPhase() {
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withoutSecret()
                    .returnFirst();

            var garageCluster = given.one()
                    .garageCluster()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            var reconciled = awaitPhase(garageCluster, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains("Secret reference not found");
        }

        @Test
        @DisplayName("An admin Secret without the referenced key should surface as an error")
        void missingSecretKey_setsErrorPhase() {
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withoutToken()
                    .returnFirst();

            var garageCluster = given.one()
                    .garageCluster()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            var reconciled = awaitPhase(garageCluster, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains("missing the referenced key");
        }

        @Test
        @DisplayName("An unreachable admin endpoint should surface as an error")
        void unreachableEndpoint_setsErrorPhase() {
            var garageCluster = given.one()
                    .garageCluster()
                    // Reserved port that nothing listens on.
                    .withAdminEndpoint("http://localhost:1")
                    .returnFirst();

            var reconciled = awaitPhase(garageCluster, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).isNotBlank();
        }
    }
}
