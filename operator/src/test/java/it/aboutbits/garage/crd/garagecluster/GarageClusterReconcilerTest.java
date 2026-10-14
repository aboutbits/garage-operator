package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.test.junit.QuarkusTest;
import it.aboutbits.garage._support.testdata.base.TestUtil;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

/// Exercises the reconciler against a real, unconfigured Garage node provided by the Compose Dev
/// Service.
///
/// The Garage container is shared by every test in this class and, unlike the Kubernetes
/// resources, its layout cannot be rolled back between tests. Assertions are therefore written
/// against *relative* changes — did the layout version advance, does the node's role now match the
/// spec — rather than against absolute version numbers.
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@RequiredArgsConstructor
@NullMarked
class GarageClusterReconcilerTest {
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
        // The Compose Dev Service starts the container, but Garage needs a moment before the
        // Admin API answers.
        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .ignoreExceptions()
                .until(() -> garageAdminApi().getClusterStatus().nodes().size() == 1);

        TestUtil.resetEnvironment(kubernetesClient);
    }

    /// Waits for the phase at the current generation, so that a phase from before an edit does not count.
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
                                && item.getStatus().getObservedGeneration() == item.getMetadata().getGeneration()
                                && item.getStatus().getPhase() == expectedPhase,
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

    private GarageCluster editLayout(
            GarageCluster garageCluster,
            Consumer<GarageClusterLayoutSpec> edit
    ) {
        return kubernetesClient.resources(GarageCluster.class)
                .inNamespace(garageCluster.getMetadata().getNamespace())
                .withName(garageCluster.getMetadata().getName())
                .edit(item -> {
                    edit.accept(item.getSpec().getLayout());
                    return item;
                });
    }

    @Nested
    class Bootstrap {
        @Test
        @DisplayName("A GarageCluster should assign and apply a layout for the node")
        void garageCluster_appliesLayout() {
            // given
            var garageCluster = given.one()
                    .garageCluster()
                    .withZone("default")
                    .withCapacity("20Gi")
                    .returnFirst();

            // when
            var reconciled = awaitPhase(garageCluster, CRPhase.READY);

            // then
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
        @DisplayName("A reordered tag list should not create a new layout version")
        void reorderedTags_isNotReapplied() {
            // given
            var garageCluster = given.one()
                    .garageCluster()
                    .withTags(List.of("ssd", "fast"))
                    .returnFirst();

            awaitPhase(garageCluster, CRPhase.READY);

            var layoutVersion = garageAdminApi().getClusterLayout().version();

            // when
            var edited = editLayout(garageCluster, layout -> layout.setTags(List.of("fast", "ssd")));

            // then
            var reconciled = awaitPhase(edited, CRPhase.READY);

            assertThat(reconciled.getStatus().getLayoutVersion()).isEqualTo(layoutVersion);
            assertThat(garageAdminApi().getClusterLayout().version()).isEqualTo(layoutVersion);
        }

        @Test
        @DisplayName("Changing the capacity should apply a new layout version")
        void changedCapacity_appliesNewLayoutVersion() {
            // given
            var garageCluster = given.one()
                    .garageCluster()
                    .withCapacity("20Gi")
                    .returnFirst();

            awaitPhase(garageCluster, CRPhase.READY);

            var layoutVersion = garageAdminApi().getClusterLayout().version();

            // when
            var edited = editLayout(garageCluster, layout -> layout.setCapacity("30Gi"));

            // then
            var reconciled = awaitPhase(edited, CRPhase.READY);

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
        @DisplayName("A second GarageCluster for the same Garage node should surface as an error, and leave the layout alone")
        void secondGarageCluster_setsErrorPhase() {
            // given
            var first = given.one()
                    .garageCluster()
                    .withCapacity("20Gi")
                    .returnFirst();

            awaitPhase(first, CRPhase.READY);

            var layoutVersion = garageAdminApi().getClusterLayout().version();

            // when
            var second = given.one()
                    .garageCluster()
                    .withCapacity("30Gi")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(second, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains(
                    "already managed by GarageCluster %s/%s".formatted(
                            first.getMetadata().getNamespace(),
                            first.getMetadata().getName()
                    )
            );
            assertThat(reconciled.getStatus().getNodeId()).isNull();
            assertThat(garageAdminApi().getClusterLayout().version()).isEqualTo(layoutVersion);

            assertThat(awaitPhase(first, CRPhase.READY).getStatus().getNodeId()).isNotBlank();
        }

        @Test
        @DisplayName("An error after READY should not keep the health of the last READY")
        void errorAfterReady_clearsHealth() {
            // given
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withToken(garageAdminToken)
                    .returnFirst();

            var garageCluster = given.one()
                    .garageCluster()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            // precondition: the health is set while READY
            assertThat(awaitPhase(garageCluster, CRPhase.READY).getStatus().getHealth()).isNotNull();

            kubernetesClient.secrets()
                    .inNamespace(adminSecretRef.getNamespace())
                    .withName(adminSecretRef.getName())
                    .edit(secret -> {
                        secret.setData(Map.of(
                                adminSecretRef.getKey(),
                                Base64.getEncoder().encodeToString("definitely-not-the-admin-token".getBytes(StandardCharsets.UTF_8))
                        ));
                        return secret;
                    });

            // when
            // A change of the spec triggers the reconcile; a Secret change alone waits for the resync.
            var edited = editLayout(garageCluster, layout -> layout.setTags(List.of("edited")));

            // then
            var status = awaitPhase(edited, CRPhase.ERROR).getStatus();

            assertThat(status.getHealth()).isNull();
            assertThat(status.getStorageNodes()).isZero();
            assertThat(status.getStorageNodesUp()).isZero();
        }

        @Test
        @DisplayName("An invalid admin token should surface as an error with the message of Garage")
        void invalidToken_setsErrorPhase() {
            // given
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withToken("definitely-not-the-admin-token")
                    .returnFirst();

            // when
            var garageCluster = given.one()
                    .garageCluster()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(garageCluster, CRPhase.ERROR);

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
            var garageCluster = given.one()
                    .garageCluster()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(garageCluster, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains("Secret reference not found");
        }

        @Test
        @DisplayName("An admin Secret without the referenced key should surface as an error")
        void missingSecretKey_setsErrorPhase() {
            // given
            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withoutToken()
                    .returnFirst();

            // when
            var garageCluster = given.one()
                    .garageCluster()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(garageCluster, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains("missing the referenced key");
        }

        @Test
        @DisplayName("An unreachable admin endpoint should surface as an error")
        void unreachableEndpoint_setsErrorPhase() {
            // when
            var garageCluster = given.one()
                    .garageCluster()
                    // Reserved port that nothing listens on.
                    .withAdminEndpoint("http://localhost:1")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(garageCluster, CRPhase.ERROR);

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
                            .garageCluster()
                            .withAdminEndpoint(adminEndpoint)
                            .returnFirst()
            );

            // then
            assertThat(thrown.getCode()).isEqualTo(422);
            assertThat(thrown.getMessage()).contains("The admin endpoint must be an http:// or https:// URL with a host.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "-1Gi", "0.5", "1m", "20 Gi", "twenty"})
        @DisplayName("A capacity that is not a whole number of bytes greater than zero should be rejected")
        void invalidCapacity_isRejected(String capacity) {
            // when
            var thrown = catchThrowableOfType(
                    KubernetesClientException.class,
                    () -> given.one()
                            .garageCluster()
                            .withCapacity(capacity)
                            .returnFirst()
            );

            // then
            assertThat(thrown.getCode()).isEqualTo(422);
            assertThat(thrown.getMessage()).contains("The capacity must be a Kubernetes quantity of whole bytes");
        }
    }
}
