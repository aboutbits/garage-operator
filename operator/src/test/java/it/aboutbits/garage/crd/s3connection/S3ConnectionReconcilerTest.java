package it.aboutbits.garage.crd.s3connection;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.test.junit.QuarkusTest;
import it.aboutbits.garage._support.testdata.base.TestUtil;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
import it.aboutbits.garage.crd.garagecluster.GarageCluster;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

/// Exercises the connection probe against the real Garage node provided by the Compose Dev
/// Service.
///
/// Every test that expects a `READY` connection first brings the cluster up through a
/// `GarageCluster`, so it does not depend on a layout another test happened to apply.
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@RequiredArgsConstructor
@NullMarked
class S3ConnectionReconcilerTest {
    private static final long AWAIT_SECONDS = 60;

    private final KubernetesClient kubernetesClient;

    private final Given given;

    private final GarageAdminClientFactory garageAdminClientFactory;

    @ConfigProperty(name = "garage.admin.port")
    private final int garageAdminPort;

    @ConfigProperty(name = "garage.admin.token")
    private final String garageAdminToken;

    private @Nullable GarageAdminApi garageAdminApi;

    private GarageAdminApi garageAdminApi() {
        if (garageAdminApi == null) {
            garageAdminApi = garageAdminClientFactory.create(
                    URI.create("http://localhost:%d".formatted(garageAdminPort)),
                    garageAdminToken
            );
        }

        return garageAdminApi;
    }

    @AfterAll
    void closeGarageAdminApi() throws IOException {
        if (garageAdminApi instanceof Closeable closeable) {
            closeable.close();
        }
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
            // given
            givenAppliedLayout();

            // when
            var s3Connection = given.one()
                    .s3Connection()
                    .returnFirst();

            // then
            var reconciled = awaitPhase(s3Connection, CRPhase.READY);

            assertThat(reconciled.getStatus().getMessage()).contains("Garage cluster");
        }

        @Test
        @DisplayName("An admin token that ends with a newline should be accepted")
        void tokenWithTrailingNewline_becomesReady() {
            // given
            givenAppliedLayout();

            // As written by `echo "$TOKEN" > token.txt` and `kubectl create secret generic --from-file`.
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withToken(garageAdminToken + "\n")
                    .returnFirst();

            // when
            var s3Connection = given.one()
                    .s3Connection()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            // then
            awaitPhase(s3Connection, CRPhase.READY);
        }
    }

    @Nested
    class Failures {
        @Test
        @DisplayName("An invalid admin token should surface as an error with the message of Garage")
        void invalidToken_setsErrorPhase() {
            // given
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withToken("definitely-not-the-admin-token")
                    .returnFirst();

            // when
            var s3Connection = given.one()
                    .s3Connection()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(s3Connection, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage())
                    .startsWith("The Garage Admin API request failed [status=403, code=")
                    .doesNotContain("code=null")
                    .doesNotContain("message=null");
        }

        @Test
        @DisplayName("A missing admin Secret should surface as an error")
        void missingSecret_setsErrorPhase() {
            // given
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withoutSecret()
                    .returnFirst();

            // when
            var s3Connection = given.one()
                    .s3Connection()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(s3Connection, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains("Secret reference not found");
        }

        @Test
        @DisplayName("An unreachable admin endpoint should surface as an error")
        void unreachableEndpoint_setsErrorPhase() {
            // when
            var s3Connection = given.one()
                    .s3Connection()
                    // Reserved port that nothing listens on.
                    .withAdminEndpoint("http://localhost:1")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(s3Connection, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).isNotBlank();
        }
    }

    @Nested
    class Validation {
        @ParameterizedTest
        @ValueSource(strings = {"http://", "http://:3903", "http:///v2", "ftp://garage:3903", "garage:3903"})
        @DisplayName("An admin endpoint that is not an http:// or https:// URL with a host should be rejected")
        void invalidAdminEndpoint_isRejected(String adminEndpoint) {
            // when
            var thrown = catchThrowableOfType(
                    KubernetesClientException.class,
                    () -> given.one()
                            .s3Connection()
                            .withAdminEndpoint(adminEndpoint)
                            .returnFirst()
            );

            // then
            assertThat(thrown.getCode()).isEqualTo(422);
            assertThat(thrown.getMessage()).contains("The admin endpoint must be an http:// or https:// URL with a host.");
        }

        @Test
        @DisplayName("Changing the admin endpoint should be rejected")
        void changedAdminEndpoint_isRejected() {
            // given
            var s3Connection = given.one()
                    .s3Connection()
                    .returnFirst();

            // when
            var thrown = catchThrowableOfType(
                    KubernetesClientException.class,
                    () -> kubernetesClient.resources(S3Connection.class)
                            .inNamespace(s3Connection.getMetadata().getNamespace())
                            .withName(s3Connection.getMetadata().getName())
                            .edit(item -> {
                                item.getSpec().setAdminEndpoint("http://other-garage.acme.svc:3903");
                                return item;
                            })
            );

            // then
            assertThat(thrown.getCode()).isEqualTo(422);
            assertThat(thrown.getMessage()).contains("The adminEndpoint is immutable.");
        }
    }
}
