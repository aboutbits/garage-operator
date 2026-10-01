package it.aboutbits.garage.crd.s3connection;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import it.aboutbits.garage._support.testdata.base.TestUtil;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
import it.aboutbits.garage.crd.garagecluster.GarageCluster;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Exercises the connection probe against the real Garage node provided by the Compose Dev
/// Service.
///
/// Every test that expects a `READY` connection first brings the cluster up through a
/// `GarageCluster`, so it does not depend on a layout another test happened to apply.
@QuarkusTest
@NullMarked
class S3ConnectionReconcilerTest {
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
        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .ignoreExceptions()
                .until(() -> garageAdminApi().getClusterStatus().nodes().size() == 1);

        TestUtil.resetEnvironment(kubernetesClient);
    }

    private S3Connection awaitPhase(
            S3Connection s3Connection,
            CRPhase expectedPhase
    ) {
        //noinspection ConstantConditions
        return kubernetesClient.resources(S3Connection.class)
                .inNamespace(s3Connection.getMetadata().getNamespace())
                .withName(s3Connection.getMetadata().getName())
                .waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getPhase() == expectedPhase,
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

    /// Make sure the cluster has a layout, so that it can actually serve requests.
    private void givenAppliedLayout() {
        var garageCluster = given.one()
                .garageCluster()
                .returnFirst();

        //noinspection ConstantConditions
        kubernetesClient.resources(GarageCluster.class)
                .inNamespace(garageCluster.getMetadata().getNamespace())
                .withName(garageCluster.getMetadata().getName())
                .waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getPhase() == CRPhase.READY,
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

    @Nested
    class Probe {
        @Test
        @DisplayName("A connection to a serving cluster should become ready")
        void servingCluster_becomesReady() {
            givenAppliedLayout();

            var s3Connection = given.one()
                    .s3Connection()
                    .returnFirst();

            var reconciled = awaitPhase(s3Connection, CRPhase.READY);

            assertThat(reconciled.getStatus().getMessage()).contains("Garage cluster");
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

            var s3Connection = given.one()
                    .s3Connection()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            var reconciled = awaitPhase(s3Connection, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).isNotBlank();
        }

        @Test
        @DisplayName("A missing admin Secret should surface as an error")
        void missingSecret_setsErrorPhase() {
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withoutSecret()
                    .returnFirst();

            var s3Connection = given.one()
                    .s3Connection()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            var reconciled = awaitPhase(s3Connection, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains("Secret reference not found");
        }

        @Test
        @DisplayName("An unreachable admin endpoint should surface as an error")
        void unreachableEndpoint_setsErrorPhase() {
            var s3Connection = given.one()
                    .s3Connection()
                    // Reserved port that nothing listens on.
                    .withAdminEndpoint("http://localhost:1")
                    .returnFirst();

            var reconciled = awaitPhase(s3Connection, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).isNotBlank();
        }
    }
}
