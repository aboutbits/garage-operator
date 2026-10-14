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
import it.aboutbits.garage.core.KubernetesService;
import it.aboutbits.garage.core.adminapi.dto.UpdateKeyRequestBody;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static it.aboutbits.garage._support.testdata.base.TestUtil.AWAIT_SECONDS;
import static it.aboutbits.garage._support.testdata.base.TestUtil.awaitPhase;
import static it.aboutbits.garage.core.BaseReconciler.RETRY_SECONDS;
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
            var connectionRef = given.readyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-created-key")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

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
            var connectionRef = given.readyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-discreet-key")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            var secret = requireSecret(accessKey.getMetadata().getName());

            var secretAccessKey = decode(secret, SECRET_DATA_SECRET_ACCESS_KEY);

            assertThat(kubernetesClient.getKubernetesSerialization().asJson(reconciled.getStatus()))
                    .doesNotContain(secretAccessKey);
        }

        @Test
        @DisplayName("A custom secretName should be honored")
        void customSecretName_isHonored() {
            // given
            var connectionRef = given.readyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-named-secret-key")
                    .withSecretName("my-own-credentials")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            assertThat(reconciled.getStatus().getSecretName()).isEqualTo("my-own-credentials");
            assertThat(secret("my-own-credentials")).isNotNull();
        }

        @Test
        @DisplayName("The Secret should be owned by the AccessKey so it is collected with it")
        void secret_isOwnedByAccessKey() {
            // given
            var connectionRef = given.readyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-owned-secret-key")
                    .returnFirst();

            // then
            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

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
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-rebuilt-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

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
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-restored-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

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
            var connectionRef = given.readyConnection();

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
            var reconciled = awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            assertThat(reconciled.getStatus().getAccessKeyId()).isEqualTo(created.accessKeyId());
            assertThat(garageAdminApi().listKeys()).hasSize(1);
        }

        @Test
        @DisplayName("A key deleted on the backend should be created again, and its new credentials written to the Secret")
        void deletedKey_isCreatedAgain() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-recreated-key")
                    .returnFirst();

            var originalAccessKeyId = Objects.requireNonNull(
                    awaitPhase(kubernetesClient, accessKey, CRPhase.READY).getStatus().getAccessKeyId()
            );

            garageAdminApi().deleteKey(originalAccessKeyId);

            // when
            // A change of the spec makes the operator look again now, not at the next resync.
            var updated = given.one()
                    .accessKey()
                    .withName(accessKey.getMetadata().getName())
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-recreated-key")
                    .withAllowCreateBucket(true)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, updated, CRPhase.READY);

            var accessKeyId = Objects.requireNonNull(reconciled.getStatus().getAccessKeyId());

            assertThat(accessKeyId).isNotEqualTo(originalAccessKeyId);

            assertThat(garageAdminApi().listKeys())
                    .singleElement()
                    .satisfies(key -> {
                        assertThat(key.id()).isEqualTo(accessKeyId);
                        assertThat(key.name()).isEqualTo("test-recreated-key");
                    });

            var secret = requireSecret(accessKey.getMetadata().getName());

            assertThat(decode(secret, SECRET_DATA_ACCESS_KEY_ID)).isEqualTo(accessKeyId);
            assertThat(decode(secret, SECRET_DATA_SECRET_ACCESS_KEY))
                    .isEqualTo(garageAdminApi().getKeyInfo(accessKeyId, true).secretAccessKey());
        }

        @Test
        @DisplayName("A Secret whose managed-by label was removed should be labeled again, so that a later deletion is still noticed")
        void unlabeledSecret_isRelabeled() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-relabeled-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            var secretName = accessKey.getMetadata().getName();
            var secret = kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .withName(secretName);

            // when
            secret.edit(item -> {
                item.getMetadata().getLabels().remove(KubernetesService.LABEL_MANAGED_BY);

                return item;
            });

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(requireSecret(secretName).getMetadata().getLabels())
                            .containsEntry(KubernetesService.LABEL_MANAGED_BY, KubernetesService.LABEL_MANAGED_BY_VALUE));

            var relabeled = requireSecret(secretName);

            // when
            secret.delete();

            // then
            // Well before the periodic reconciliation: the label puts the Secret back under watch.
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(requireSecret(secretName).getMetadata().getUid())
                            .isNotEqualTo(relabeled.getMetadata().getUid()));
        }

        @Test
        @DisplayName("A key added to the Secret by hand should be kept, and not make the operator write the Secret again")
        void addedSecretKey_isKept() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-extended-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            var secretName = accessKey.getMetadata().getName();

            // As a team would add the S3 endpoint, which the operator does not write.
            kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .resource(new SecretBuilder()
                            .withNewMetadata()
                            .withName(secretName)
                            .endMetadata()
                            .addToStringData("endpoint", "http://garage:3900")
                            .build()
                    )
                    .fieldManager("kubectl-edit")
                    .serverSideApply();

            // when
            // A change of the spec makes the operator reconcile now, with the added key in place.
            var updated = given.one()
                    .accessKey()
                    .withName(accessKey.getMetadata().getName())
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-extended-key")
                    .withAllowCreateBucket(true)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, updated, CRPhase.READY);

            assertThat(Objects.toString(reconciled.getStatus().getMessage(), ""))
                    .doesNotContain("Credentials written");

            var secret = requireSecret(secretName);

            assertThat(decode(secret, "endpoint")).isEqualTo("http://garage:3900");
            assertThat(decode(secret, SECRET_DATA_ACCESS_KEY_ID)).isEqualTo(reconciled.getStatus().getAccessKeyId());
        }
    }

    @Nested
    class Permissions {
        @Test
        @DisplayName("allowCreateBucket should be applied on the backend")
        void allowCreateBucket_isApplied() {
            // given
            var connectionRef = given.readyConnection();

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-permissive-key")
                    .withAllowCreateBucket(true)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

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
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-revoked-key")
                    .withAllowCreateBucket(true)
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            // when
            var revoked = given.one()
                    .accessKey()
                    .withName(accessKey.getMetadata().getName())
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-revoked-key")
                    .withAllowCreateBucket(false)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, revoked, CRPhase.READY);

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
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-deleted-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

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

        @Test
        @DisplayName("An AccessKey whose key is already gone from the backend should still be deletable")
        void keyGoneFromBackend_isReleased() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-vanished-key")
                    .returnFirst();

            var accessKeyId = Objects.requireNonNull(
                    awaitPhase(kubernetesClient, accessKey, CRPhase.READY).getStatus().getAccessKeyId()
            );

            garageAdminApi().deleteKey(accessKeyId);

            // when
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);

            // then
            // Only the recorded key: a reconcile caused by the new Secret can create the key again in the meantime.
            assertThat(garageAdminApi().listKeys())
                    .noneSatisfy(key -> assertThat(key.id()).isEqualTo(accessKeyId));
        }

        @Test
        @DisplayName("An AccessKey that never reached the backend should be released even without its S3Connection")
        void neverCreatedAccessKey_isReleasedWithoutConnection() {
            // given
            var connectionRef = new ResourceRef();

            connectionRef.setNamespace(kubernetesClient.getNamespace());
            connectionRef.setName("does-not-exist");

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-unborn-key")
                    .returnFirst();

            var pending = awaitPhase(kubernetesClient, accessKey, CRPhase.PENDING);

            // precondition: with a recorded key, the cleanup would wait for the connection instead
            assertThat(pending.getStatus().getAccessKeyId()).isNull();

            var resource = kubernetesClient.resources(AccessKey.class)
                    .inNamespace(accessKey.getMetadata().getNamespace())
                    .withName(accessKey.getMetadata().getName());

            // when
            resource.delete();

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> resource.get() == null);
        }
    }

    @Nested
    class Ownership {
        @Test
        @DisplayName("A second AccessKey for a key that is already managed should be refused, and deleting it should leave the key alone")
        void secondAccessKeyForManagedKey_isRefused() {
            // given
            var connectionRef = given.readyConnection();

            var first = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-shared-key")
                    .returnFirst();

            var firstAccessKeyId = Objects.requireNonNull(
                    awaitPhase(kubernetesClient, first, CRPhase.READY).getStatus().getAccessKeyId()
            );

            // when
            var second = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-shared-key")
                    .returnFirst();

            // then
            var refused = awaitPhase(kubernetesClient, second, CRPhase.ERROR);

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
            var connectionRef = given.readyConnection();

            garageAdminApi().createKey(UpdateKeyRequestBody.named("test-twin-key"));
            garageAdminApi().createKey(UpdateKeyRequestBody.named("test-twin-key"));

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-twin-key")
                    .returnFirst();

            // then
            var refused = awaitPhase(kubernetesClient, accessKey, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains("More than one access key is named test-twin-key");
            assertThat(garageAdminApi().listKeys()).hasSize(2);
            assertThat(secret(accessKey.getMetadata().getName())).isNull();
        }

        @Test
        @DisplayName("Deleting an AccessKey should delete its key even if the key was renamed on the backend")
        void renamedKey_isStillDeleted() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-renamed-key")
                    .returnFirst();

            var accessKeyId = Objects.requireNonNull(
                    awaitPhase(kubernetesClient, accessKey, CRPhase.READY).getStatus().getAccessKeyId()
            );

            garageAdminApi().updateKey(accessKeyId, UpdateKeyRequestBody.named("renamed-out-of-band"));

            // when
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);

            // then
            assertThat(garageAdminApi().listKeys())
                    .noneSatisfy(key -> assertThat(key.id()).isEqualTo(accessKeyId));
        }

        @Test
        @DisplayName("An existing Secret the AccessKey does not own should be left untouched, and no key be created")
        void unownedSecret_isLeftUntouched() {
            // given
            var connectionRef = given.readyConnection();

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
            var refused = awaitPhase(kubernetesClient, accessKey, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains("not owned by this resource");

            var secret = requireSecret("test-foreign-secret");

            assertThat(secret.getMetadata().getOwnerReferences()).isNullOrEmpty();
            assertThat(secret.getData()).containsOnlyKeys("unrelated");

            // Refused before the key is created, so no key is left whose secret nobody can read.
            assertThat(refused.getStatus().getAccessKeyId()).isNull();
            assertThat(garageAdminApi().listKeys()).isEmpty();

            // when
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);

            // then
            assertThat(secret("test-foreign-secret")).isNotNull();

            kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .withName("test-foreign-secret")
                    .delete();
        }

        @Test
        @DisplayName("An existing key whose Secret name is taken by a foreign Secret should not be adopted, and survive the deletion")
        void unownedSecretForExistingKey_isNotAdopted() {
            // given
            var connectionRef = given.readyConnection();

            var existing = garageAdminApi().createKey(
                    UpdateKeyRequestBody.named("test-foreign-existing-key")
            );

            kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .resource(new SecretBuilder()
                            .withNewMetadata()
                            .withName("test-foreign-existing-secret")
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
                    .withKeyName("test-foreign-existing-key")
                    .withSecretName("test-foreign-existing-secret")
                    .returnFirst();

            // then
            var refused = awaitPhase(kubernetesClient, accessKey, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains("not owned by this resource");
            assertThat(refused.getStatus().getAccessKeyId()).isNull();

            // when
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);

            // then
            assertThat(garageAdminApi().listKeys())
                    .anySatisfy(key -> assertThat(key.id()).isEqualTo(existing.accessKeyId()));

            kubernetesClient.secrets()
                    .inNamespace(kubernetesClient.getNamespace())
                    .withName("test-foreign-existing-secret")
                    .delete();
        }
    }

    @Nested
    class Immutability {
        @Test
        @DisplayName("Changing the connectionRef should be rejected by the API server")
        void changedConnectionRef_isRejected() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-pinned-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            var otherConnectionRef = new ResourceRef();

            otherConnectionRef.setNamespace(connectionRef.getNamespace());
            otherConnectionRef.setName("another-connection");

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

        @Test
        @DisplayName("Changing the name should be rejected by the API server")
        void changedName_isRejected() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-named-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .accessKey()
                            .withName(accessKey.getMetadata().getName())
                            .withConnectionRef(connectionRef)
                            .withKeyName("test-renamed-key")
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("The AccessKey name is immutable");
        }

        @Test
        @DisplayName("Changing the secretName should be rejected by the API server")
        void changedSecretName_isRejected() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-pinned-secret-key")
                    .withSecretName("app-s3")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .accessKey()
                            .withName(accessKey.getMetadata().getName())
                            .withConnectionRef(connectionRef)
                            .withKeyName("test-pinned-secret-key")
                            .withSecretName("app-s3-v2")
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("The secretName is immutable");
        }

        @Test
        @DisplayName("Setting a secretName that was left out should be rejected by the API server")
        void addedSecretName_isRejected() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-default-secret-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .accessKey()
                            .withName(accessKey.getMetadata().getName())
                            .withConnectionRef(connectionRef)
                            .withKeyName("test-default-secret-key")
                            .withSecretName("app-s3")
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("The secretName is immutable");
        }

        @Test
        @DisplayName("Removing the secretName should be rejected by the API server")
        void removedSecretName_isRejected() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-dropped-secret-key")
                    .withSecretName("app-s3")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .accessKey()
                            .withName(accessKey.getMetadata().getName())
                            .withConnectionRef(connectionRef)
                            .withKeyName("test-dropped-secret-key")
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("The secretName is immutable");
        }
    }

    @Nested
    class Naming {
        @Test
        @DisplayName("A blank key name should be rejected by the API server")
        void blankName_isRejected() {
            // given
            var connectionRef = given.readyConnection();

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .accessKey()
                            .withConnectionRef(connectionRef)
                            .withKeyName(" ")
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("spec.name")
                    .hasMessageContaining("The AccessKey name must not be empty.");
        }

        @Test
        @DisplayName("A blank or invalid secretName should be rejected by the API server")
        void invalidSecretName_isRejected() {
            // given
            var connectionRef = given.readyConnection();

            assertThat(List.of("", " ", "Not_A_Name"))
                    .allSatisfy(invalidSecretName -> {
                        // when
                        var thrown = catchThrowable(
                                () -> given.one()
                                        .accessKey()
                                        .withConnectionRef(connectionRef)
                                        .withSecretName(invalidSecretName)
                                        .returnFirst()
                        );

                        // then
                        assertThat(thrown)
                                .as("Expected the secretName to be rejected: '%s'", invalidSecretName)
                                .isInstanceOf(RuntimeException.class)
                                .hasMessageContaining("spec.secretName");
                    });
        }
    }

    @Nested
    class Teardown {
        @Test
        @DisplayName("Deleting a namespace whose S3Connection is already gone should not be blocked by its AccessKeys")
        void namespaceDeletion_isNotBlocked() {
            // given
            var namespace = TestDataCreator.randomKubernetesNameSuffix("test-teardown");

            kubernetesClient.namespaces()
                    .resource(new NamespaceBuilder()
                            .withNewMetadata()
                            .withName(namespace)
                            .endMetadata()
                            .build()
                    )
                    .create();

            var connectionRef = given.readyConnection(namespace);

            var accessKey = given.one()
                    .accessKey()
                    .withNamespace(namespace)
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-teardown-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            // when
            // What namespace deletion does to a Garage installed in that namespace: the
            // connection goes away, independently of anything that depends on it.
            kubernetesClient.resources(S3Connection.class)
                    .inNamespace(namespace)
                    .withName(connectionRef.getName())
                    .delete();

            kubernetesClient.namespaces()
                    .withName(namespace)
                    .delete();

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> kubernetesClient.namespaces().withName(namespace).get() == null);

            // Released without the backend: nothing could delete the key without its connection.
            assertThat(garageAdminApi().listKeys())
                    .anySatisfy(key -> assertThat(key.name()).isEqualTo("test-teardown-key"));
        }

        @Test
        @DisplayName("An AccessKey whose S3Connection is gone should wait for it, and delete the key once it is back")
        void lostConnection_blocksDeletionUntilBack() {
            // given
            var connectionRef = given.readyConnection();

            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-orphaned-key")
                    .returnFirst();

            var accessKeyId = Objects.requireNonNull(
                    awaitPhase(kubernetesClient, accessKey, CRPhase.READY).getStatus().getAccessKeyId()
            );

            var s3Connection = kubernetesClient.resources(S3Connection.class)
                    .inNamespace(connectionRef.getNamespace())
                    .withName(connectionRef.getName());

            s3Connection.delete();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> s3Connection.get() == null);

            var resource = kubernetesClient.resources(AccessKey.class)
                    .inNamespace(accessKey.getMetadata().getNamespace())
                    .withName(accessKey.getMetadata().getName());

            // when
            resource.delete();

            // then
            //noinspection ConstantConditions
            var blocked = resource.waitUntilCondition(
                    item -> item != null
                            && item.getStatus() != null
                            && item.getStatus().getMessage() != null
                            && item.getStatus().getMessage().contains("no longer exists or is not ready yet"),
                    AWAIT_SECONDS,
                    TimeUnit.SECONDS
            );

            assertThat(blocked.getStatus().getPhase()).isEqualTo(CRPhase.DELETING);
            assertThat(garageAdminApi().listKeys())
                    .anySatisfy(key -> assertThat(key.id()).isEqualTo(accessKeyId));

            // when
            given.one()
                    .s3Connection()
                    .withNamespace(connectionRef.getNamespace())
                    .withName(connectionRef.getName())
                    .returnFirst();

            // then
            // The AccessKey does not watch its S3Connection, so only the retry of the cleanup notices it.
            await().atMost(RETRY_SECONDS + AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> resource.get() == null);

            assertThat(garageAdminApi().listKeys())
                    .noneSatisfy(key -> assertThat(key.id()).isEqualTo(accessKeyId));
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
            given.appliedLayout();

            var connectionRef = new ResourceRef();

            connectionRef.setNamespace(kubernetesClient.getNamespace());
            connectionRef.setName("does-not-exist");

            // when
            var accessKey = given.one()
                    .accessKey()
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-ungated-key")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, accessKey, CRPhase.PENDING);

            assertThat(reconciled.getStatus().getMessage()).contains("does not exist or is not ready yet");
            assertThat(garageAdminApi().listKeys()).isEmpty();
            assertThat(secret(accessKey.getMetadata().getName())).isNull();
        }
    }
}
