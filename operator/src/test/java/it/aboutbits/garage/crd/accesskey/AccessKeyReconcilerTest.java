package it.aboutbits.garage.crd.accesskey;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage._support.testdata.base.TestUtil;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
import it.aboutbits.garage.core.adminapi.dto.UpdateKeyRequestBody;
import it.aboutbits.garage.crd.garagecluster.GarageCluster;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static it.aboutbits.garage.crd.accesskey.AccessKeyReconciler.SECRET_DATA_ACCESS_KEY_ID;
import static it.aboutbits.garage.crd.accesskey.AccessKeyReconciler.SECRET_DATA_SECRET_ACCESS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/// Exercises access key management against the real Garage node provided by the Compose Dev
/// Service.
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@RequiredArgsConstructor
@NullMarked
class AccessKeyReconcilerTest {
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

        // Keys can outlive a failed test, and bucket/key calls need a serving cluster.
        try {
            garageAdminApi().listKeys().forEach(key -> garageAdminApi().deleteKey(key.id()));
        } catch (RuntimeException e) {
            log.debug("Skipping access key cleanup, the Garage cluster is not serving yet", e);
        }
    }

    private AccessKey awaitPhase(
            AccessKey accessKey,
            CRPhase expectedPhase
    ) {
        //noinspection ConstantConditions
        return kubernetesClient.resources(AccessKey.class)
                .inNamespace(accessKey.getMetadata().getNamespace())
                .withName(accessKey.getMetadata().getName())
                .waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getPhase() == expectedPhase,
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

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

    private ResourceRef givenReadyConnection() {
        givenAppliedLayout();

        var s3Connection = given.one()
                .s3Connection()
                .returnFirst();

        //noinspection ConstantConditions
        kubernetesClient.resources(S3Connection.class)
                .inNamespace(s3Connection.getMetadata().getNamespace())
                .withName(s3Connection.getMetadata().getName())
                .waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getPhase() == CRPhase.READY,
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );

        var connectionRef = new ResourceRef();

        connectionRef.setName(s3Connection.getMetadata().getName());
        connectionRef.setNamespace(s3Connection.getMetadata().getNamespace());

        return connectionRef;
    }

    private @Nullable Secret secret(String name) {
        return kubernetesClient.secrets()
                .inNamespace(kubernetesClient.getNamespace())
                .withName(name)
                .get();
    }

    /// The Secret, asserted to exist.
    private Secret requireSecret(String name) {
        var secret = secret(name);

        assertThat(secret).isNotNull();

        return Objects.requireNonNull(secret);
    }

    private static String decode(
            Secret secret,
            String key
    ) {
        return new String(
                Base64.getDecoder().decode(secret.getData().get(key)),
                StandardCharsets.UTF_8
        );
    }

    @Nested
    class Create {
        @Test
        @DisplayName("An AccessKey should be created and its credentials written to a Secret")
        void accessKey_isCreatedWithSecret() {
            // given
            var connectionRef = givenReadyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-created-key")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(accessKey, CRPhase.READY);

            var status = reconciled.getStatus();

            assertThat(status.getAccessKeyId()).isNotBlank();
            assertThat(status.getSecretName()).isEqualTo(accessKey.getMetadata().getName());

            // The key exists on the backend under the name from the spec.
            assertThat(garageAdminApi().listKeys())
                    .anySatisfy(key -> assertThat(key.name()).isEqualTo("test-created-key"));

            var secret = requireSecret(accessKey.getMetadata().getName());

            assertThat(decode(secret, SECRET_DATA_ACCESS_KEY_ID)).isEqualTo(status.getAccessKeyId());
            assertThat(decode(secret, SECRET_DATA_SECRET_ACCESS_KEY)).isNotBlank();
        }

        @Test
        @DisplayName("The secret half of the credentials should never appear in the status")
        void secret_isNotInStatus() {
            // given
            var connectionRef = givenReadyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-discreet-key")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(accessKey, CRPhase.READY);

            var secret = requireSecret(accessKey.getMetadata().getName());

            var secretAccessKey = decode(secret, SECRET_DATA_SECRET_ACCESS_KEY);

            assertThat(reconciled.getStatus().getMessage()).doesNotContain(secretAccessKey);
        }

        @Test
        @DisplayName("A custom secretName should be honoured")
        void customSecretName_isHonoured() {
            // given
            var connectionRef = givenReadyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-named-secret-key")
                    .withSecretName("my-own-credentials")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(accessKey, CRPhase.READY);

            assertThat(reconciled.getStatus().getSecretName()).isEqualTo("my-own-credentials");
            assertThat(secret("my-own-credentials")).isNotNull();
        }

        @Test
        @DisplayName("The Secret should be owned by the AccessKey so it is collected with it")
        void secret_isOwnedByAccessKey() {
            // given
            var connectionRef = givenReadyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-owned-secret-key")
                    .returnFirst();

            // then
            awaitPhase(accessKey, CRPhase.READY);

            var secret = requireSecret(accessKey.getMetadata().getName());

            assertThat(secret.getMetadata().getOwnerReferences())
                    .singleElement()
                    .satisfies(ownerReference -> {
                        assertThat(ownerReference.getKind()).isEqualTo("AccessKey");
                        assertThat(ownerReference.getName()).isEqualTo(accessKey.getMetadata().getName());
                    });
        }
    }

    @Nested
    class Recovery {
        @Test
        @DisplayName("A deleted Secret should be written again from the backend")
        void deletedSecret_isRebuilt() {
            // given
            var connectionRef = givenReadyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-rebuilt-key")
                    .returnFirst();

            awaitPhase(accessKey, CRPhase.READY);

            var secretName = accessKey.getMetadata().getName();
            var original = requireSecret(secretName);
            var originalSecretAccessKey = decode(original, SECRET_DATA_SECRET_ACCESS_KEY);

            // when
            kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .withName(secretName)
                    .delete();

            // then
            // Nothing else changes: the watch on the Secret alone has to trigger the rebuild, well
            // before the periodic reconciliation would, and with the very same credentials rather
            // than rotated ones. A new UID proves it really is a new Secret.
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        var rebuilt = requireSecret(secretName);

                        assertThat(rebuilt.getMetadata().getUid()).isNotEqualTo(original.getMetadata().getUid());
                        assertThat(decode(rebuilt, SECRET_DATA_SECRET_ACCESS_KEY)).isEqualTo(originalSecretAccessKey);
                    });
        }

        @Test
        @DisplayName("A Secret edited by hand should be put back")
        void editedSecret_isRestored() {
            // given
            var connectionRef = givenReadyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-restored-key")
                    .returnFirst();

            awaitPhase(accessKey, CRPhase.READY);

            var secretName = accessKey.getMetadata().getName();
            var originalSecretAccessKey = decode(requireSecret(secretName), SECRET_DATA_SECRET_ACCESS_KEY);

            // when
            // Applied under a field manager of its own, the way `kubectl` would, so the edited
            // field changes hands and a plain server-side apply would conflict on it.
            kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .resource(new SecretBuilder()
                            .withNewMetadata()
                            .withName(secretName)
                            .endMetadata()
                            .addToStringData(SECRET_DATA_SECRET_ACCESS_KEY, "tampered")
                            .build()
                    )
                    .fieldManager("kubectl-edit")
                    .forceConflicts()
                    .serverSideApply();

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(decode(requireSecret(secretName), SECRET_DATA_SECRET_ACCESS_KEY))
                                .isEqualTo(originalSecretAccessKey);
                    });
        }

        @Test
        @DisplayName("An existing key should be adopted rather than duplicated")
        void existingKey_isAdopted() {
            // given
            var connectionRef = givenReadyConnection();

            // Someone created it out of band, before the CR existed.
            var created = garageAdminApi().createKey(
                    UpdateKeyRequestBody.named("test-adopted-key")
            );

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-adopted-key")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(accessKey, CRPhase.READY);

            assertThat(reconciled.getStatus().getAccessKeyId()).isEqualTo(created.accessKeyId());
            assertThat(garageAdminApi().listKeys()).hasSize(1);
        }
    }

    @Nested
    class Permissions {
        @Test
        @DisplayName("allowCreateBucket should be applied on the backend")
        void allowCreateBucket_isApplied() {
            // given
            var connectionRef = givenReadyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-permissive-key")
                    .withAllowCreateBucket(true)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(accessKey, CRPhase.READY);

            var keyInfo = garageAdminApi().getKeyInfo(
                    Objects.requireNonNull(reconciled.getStatus().getAccessKeyId()),
                    false
            );

            assertThat(keyInfo.permissions().createBucket()).isTrue();
        }

        @Test
        @DisplayName("Revoking allowCreateBucket should be applied on the backend")
        void revokedCreateBucket_isApplied() {
            // given
            var connectionRef = givenReadyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-revoked-key")
                    .withAllowCreateBucket(true)
                    .returnFirst();

            awaitPhase(accessKey, CRPhase.READY);

            // when
            var revoked = given.one()
                    .accessKey()
                    .withName(accessKey.getMetadata().getName())
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-revoked-key")
                    .withAllowCreateBucket(false)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(revoked, CRPhase.READY);

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(
                            garageAdminApi().getKeyInfo(
                                    Objects.requireNonNull(reconciled.getStatus().getAccessKeyId()),
                                    false
                            ).permissions().createBucket()
                    ).isFalse());
        }
    }

    @Nested
    class Delete {
        @Test
        @DisplayName("Deleting an AccessKey should delete the key and its Secret")
        void deletedAccessKey_removesKeyAndSecret() {
            // given
            var connectionRef = givenReadyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-deleted-key")
                    .returnFirst();

            awaitPhase(accessKey, CRPhase.READY);

            var secretName = accessKey.getMetadata().getName();

            // precondition: otherwise the Secret being gone afterwards would prove nothing
            assertThat(secret(secretName)).isNotNull();

            // when
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);

            // then
            assertThat(garageAdminApi().listKeys())
                    .noneSatisfy(key -> assertThat(key.name()).isEqualTo("test-deleted-key"));

            // The Secret is garbage-collected by Kubernetes through its owner reference.
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> secret(secretName) == null);
        }
    }

    @Nested
    class Ownership {
        @Test
        @DisplayName("A second AccessKey for a key that is already managed should be refused, and deleting it should leave the key alone")
        void secondAccessKeyForManagedKey_isRefused() {
            // given
            var connectionRef = givenReadyConnection();

            var first = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-shared-key")
                    .returnFirst();

            var firstAccessKeyId = Objects.requireNonNull(
                    awaitPhase(first, CRPhase.READY).getStatus().getAccessKeyId()
            );

            // when
            var second = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-shared-key")
                    .returnFirst();

            // then
            var refused = awaitPhase(second, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains("already managed by AccessKey");
            assertThat(refused.getStatus().getAccessKeyId()).isNull();
            assertThat(secret(second.getMetadata().getName())).isNull();

            // when
            deleteAndAwaitGone(second);

            // then
            assertThat(garageAdminApi().listKeys())
                    .singleElement()
                    .satisfies(key -> assertThat(key.id()).isEqualTo(firstAccessKeyId));
        }

        @Test
        @DisplayName("A name that more than one key carries should be refused rather than guessed")
        void ambiguousName_isRefused() {
            // given
            var connectionRef = givenReadyConnection();

            garageAdminApi().createKey(UpdateKeyRequestBody.named("test-twin-key"));
            garageAdminApi().createKey(UpdateKeyRequestBody.named("test-twin-key"));

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-twin-key")
                    .returnFirst();

            // then
            var refused = awaitPhase(accessKey, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains("More than one access key is named test-twin-key");
            assertThat(garageAdminApi().listKeys()).hasSize(2);
            assertThat(secret(accessKey.getMetadata().getName())).isNull();
        }

        @Test
        @DisplayName("Deleting an AccessKey should delete its key even if the key was renamed on the backend")
        void renamedKey_isStillDeleted() {
            // given
            var connectionRef = givenReadyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-renamed-key")
                    .returnFirst();

            var accessKeyId = Objects.requireNonNull(
                    awaitPhase(accessKey, CRPhase.READY).getStatus().getAccessKeyId()
            );

            garageAdminApi().updateKey(accessKeyId, UpdateKeyRequestBody.named("renamed-out-of-band"));

            // when
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);

            // then
            assertThat(garageAdminApi().listKeys())
                    .noneSatisfy(key -> assertThat(key.id()).isEqualTo(accessKeyId));
        }

        @Test
        @DisplayName("An existing Secret the AccessKey does not own should be left untouched")
        void unownedSecret_isLeftUntouched() {
            // given
            var connectionRef = givenReadyConnection();

            kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .resource(new SecretBuilder()
                            .withNewMetadata()
                            .withName("test-foreign-secret")
                            .endMetadata()
                            .withType("Opaque")
                            .addToStringData("unrelated", "value")
                            .build()
                    )
                    .serverSideApply();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-foreign-secret-key")
                    .withSecretName("test-foreign-secret")
                    .returnFirst();

            // then
            var refused = awaitPhase(accessKey, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains("not owned by this resource");

            var secret = requireSecret("test-foreign-secret");

            assertThat(secret.getMetadata().getOwnerReferences()).isNullOrEmpty();
            assertThat(secret.getData()).containsOnlyKeys("unrelated");

            // The key was created before the Secret was refused. It is recorded, so deleting the
            // AccessKey still cleans it up rather than leaving a live credential behind.
            assertThat(refused.getStatus().getAccessKeyId()).isNotNull();

            // when
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);

            // then
            assertThat(garageAdminApi().listKeys()).isEmpty();
            assertThat(secret("test-foreign-secret")).isNotNull();

            kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .withName("test-foreign-secret")
                    .delete();
        }
    }

    @Nested
    class Immutability {
        @Test
        @DisplayName("Changing the connectionRef should be rejected by the API server")
        void changedConnectionRef_isRejected() {
            // given
            var connectionRef = givenReadyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-pinned-key")
                    .returnFirst();

            awaitPhase(accessKey, CRPhase.READY);

            var otherConnectionRef = new ResourceRef();

            otherConnectionRef.setName("another-connection");
            otherConnectionRef.setNamespace(connectionRef.getNamespace());

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .accessKey()
                            .withName(accessKey.getMetadata().getName())
                            .withConnectionRef(otherConnectionRef)
                            .withKeyName("test-pinned-key")
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("connectionRef is immutable");
        }
    }

    @Nested
    class Teardown {
        @Test
        @DisplayName("Deleting a namespace whose S3Connection is already gone should not be blocked by its AccessKeys")
        void namespaceDeletion_isNotBlocked() {
            // given
            givenAppliedLayout();

            var namespace = TestDataCreator.randomKubernetesNameSuffix("test-teardown");

            kubernetesClient.namespaces()
                    .resource(new NamespaceBuilder()
                            .withNewMetadata()
                            .withName(namespace)
                            .endMetadata()
                            .build()
                    )
                    .create();

            var s3Connection = given.one()
                    .s3Connection()
                    .withNamespace(namespace)
                    .returnFirst();

            //noinspection ConstantConditions
            kubernetesClient.resources(S3Connection.class)
                    .inNamespace(namespace)
                    .withName(s3Connection.getMetadata().getName())
                    .waitUntilCondition(
                            item -> item != null
                                    && item.getStatus() != null
                                    && item.getStatus().getPhase() == CRPhase.READY,
                            AWAIT_SECONDS,
                            TimeUnit.SECONDS
                    );

            var connectionRef = new ResourceRef();

            connectionRef.setName(s3Connection.getMetadata().getName());
            connectionRef.setNamespace(namespace);

            var accessKey = given.one()
                    .accessKey()
                    .withNamespace(namespace)
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-teardown-key")
                    .returnFirst();

            awaitPhase(accessKey, CRPhase.READY);

            // when
            // What namespace deletion does to a Garage installed in that namespace: the
            // connection goes away, independently of anything that depends on it.
            kubernetesClient.resources(S3Connection.class)
                    .inNamespace(namespace)
                    .withName(s3Connection.getMetadata().getName())
                    .delete();

            kubernetesClient.namespaces()
                    .withName(namespace)
                    .delete();

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> kubernetesClient.namespaces().withName(namespace).get() == null);
        }
    }

    private void deleteAndAwaitGone(AccessKey accessKey) {
        var resource = kubernetesClient.resources(AccessKey.class)
                .inNamespace(accessKey.getMetadata().getNamespace())
                .withName(accessKey.getMetadata().getName());

        resource.delete();

        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> resource.get() == null);
    }

    @Nested
    class Gating {
        @Test
        @DisplayName("An AccessKey referencing an unknown S3Connection should stay pending")
        void unknownConnection_staysPending() {
            // given
            givenAppliedLayout();

            var connectionRef = new ResourceRef();

            connectionRef.setName("does-not-exist");
            connectionRef.setNamespace(kubernetesClient.getNamespace());

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-ungated-key")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(accessKey, CRPhase.PENDING);

            assertThat(reconciled.getStatus().getMessage()).contains("does not exist or is not ready yet");
            assertThat(garageAdminApi().listKeys()).isEmpty();
            assertThat(secret(accessKey.getMetadata().getName())).isNull();
        }
    }
}
