package it.aboutbits.garage.crd.bucketaccess;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage._support.testdata.base.TestUtil;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.ReclaimPolicy;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
import it.aboutbits.garage.core.adminapi.dto.ApiBucketKeyPerm;
import it.aboutbits.garage.core.adminapi.dto.GetBucketInfoKey;
import it.aboutbits.garage.crd.accesskey.AccessKey;
import it.aboutbits.garage.crd.bucket.Bucket;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

/// Exercises grants against the real Garage node provided by the Compose Dev Service.
///
/// Besides checking what the Admin API reports, the end-to-end tests use the credentials the
/// operator wrote into the `AccessKey`'s Secret to talk to Garage's S3 API — which is the only
/// check that the whole chain, from resources to a working client, actually holds together.
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@RequiredArgsConstructor
@NullMarked
class BucketAccessReconcilerTest {
    /// Must match `s3_region` in the Dev Service's garage.toml.
    private static final Region GARAGE_REGION = Region.of("garage");

    private final KubernetesClient kubernetesClient;

    private final Given given;

    private final GarageAdminClientFactory garageAdminClientFactory;

    @ConfigProperty(name = "garage.admin.port")
    private final int garageAdminPort;

    @ConfigProperty(name = "garage.s3.port")
    private final int garageS3Port;

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

        // Retained buckets and leftover keys outlive their resources. Bucket and key calls need a
        // serving cluster; before any layout is applied there is nothing to clean up anyway.
        try {
            garageAdminApi().listKeys().forEach(key -> garageAdminApi().deleteKey(key.id()));
            garageAdminApi().listBuckets().forEach(bucket -> garageAdminApi().deleteBucket(bucket.id()));
        } catch (RuntimeException e) {
            log.debug("Skipping backend cleanup, the Garage cluster is not serving yet", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private ResourceRef givenReadyBucket(
            ResourceRef connectionRef,
            String bucketName
    ) {
        var bucket = given.one()
                .bucket()
                .withConnectionRef(connectionRef)
                .withBucketName(bucketName)
                .returnFirst();

        awaitPhase(kubernetesClient, bucket, CRPhase.READY);

        return ref(bucket.getMetadata().getName());
    }

    private ResourceRef givenReadyAccessKey(
            ResourceRef connectionRef,
            String keyName
    ) {
        var accessKey = given.one()
                .accessKey()
                .withConnectionRef(connectionRef)
                .withKeyName(keyName)
                .returnFirst();

        awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

        return ref(accessKey.getMetadata().getName());
    }

    /// A second connection to the same Garage, without a second GarageCluster, which the operator refuses.
    private ResourceRef givenSecondConnection() {
        var s3Connection = awaitPhase(
                kubernetesClient,
                given.one()
                        .s3Connection()
                        .returnFirst(),
                CRPhase.READY
        );

        return ref(s3Connection.getMetadata().getNamespace(), s3Connection.getMetadata().getName());
    }

    private ResourceRef ref(String name) {
        return ref(kubernetesClient.getNamespace(), name);
    }

    private ResourceRef ref(
            String namespace,
            String name
    ) {
        var ref = new ResourceRef();

        ref.setNamespace(namespace);
        ref.setName(name);

        return ref;
    }

    /// What Garage itself records for the key on the bucket, by backend identifiers.
    private ApiBucketKeyPerm backendPermissions(BucketAccess bucketAccess) {
        var status = bucketAccess.getStatus();

        var bucketId = Objects.requireNonNull(status.getBucketId());
        var accessKeyId = Objects.requireNonNull(status.getAccessKeyId());

        return garageAdminApi().getBucketInfo(bucketId).keys().stream()
                .filter(key -> key.accessKeyId().equals(accessKeyId))
                .findFirst()
                .map(GetBucketInfoKey::permissions)
                .orElse(new ApiBucketKeyPerm(false, false, false));
    }

    /// An S3 client authenticated with the credentials the operator wrote for the AccessKey.
    private S3Client s3ClientFor(ResourceRef accessKeyRef) {
        var accessKey = kubernetesClient.resources(AccessKey.class)
                .inNamespace(accessKeyRef.getNamespace())
                .withName(accessKeyRef.getName())
                .get();

        var secretName = Objects.requireNonNull(accessKey.getStatus().getSecretName());

        var secret = kubernetesClient.secrets()
                .inNamespace(accessKeyRef.getNamespace())
                .withName(secretName)
                .get();

        var data = secret.getData();

        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:%d".formatted(garageS3Port)))
                .region(GARAGE_REGION)
                // Garage addresses buckets by path; virtual-host style needs DNS set up for it.
                .forcePathStyle(true)
                // TODO: Remove these two lines once https://git.deuxfleurs.fr/Deuxfleurs/garage/issues/1549
                //  is fixed. Garage rejects the signed checksum trailer that the AWS SDK sends over
                //  plain HTTP by default (SDK 2.30 and later, seen on Garage v2.3.0 and v2.4.1).
                //  Applications need the same setting against Garage.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        decode(Objects.requireNonNull(data.get(SECRET_DATA_ACCESS_KEY_ID))),
                        decode(Objects.requireNonNull(data.get(SECRET_DATA_SECRET_ACCESS_KEY)))
                )))
                .build();
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    @Nested
    class Grant {
        @Test
        @DisplayName("The listed permissions should be granted on the backend, and nothing more")
        void permissions_areGranted() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-grant-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-grant-key");

            // when
            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            var permissions = backendPermissions(reconciled);

            assertThat(permissions.read()).isTrue();
            assertThat(permissions.write()).isTrue();
            assertThat(permissions.owner()).isFalse();
        }

        @Test
        @DisplayName("Removing a permission from the list should revoke it on the backend")
        void droppedPermission_isRevoked() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-downgrade-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-downgrade-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE, BucketPermission.OWNER)
                    .returnFirst();

            awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            // when
            var updated = given.one()
                    .bucketAccess()
                    .withName(bucketAccess.getMetadata().getName())
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, updated, CRPhase.READY);

            var permissions = backendPermissions(reconciled);

            assertThat(permissions.read()).isTrue();
            assertThat(permissions.write()).isFalse();
            assertThat(permissions.owner()).isFalse();
        }

        @Test
        @DisplayName("A second BucketAccess for the same bucket and key should be refused")
        void secondGrantOnSamePair_isRefused() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-shared-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-shared-key");

            var first = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            awaitPhase(kubernetesClient, first, CRPhase.READY);

            // when
            var second = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            // then
            var refused = awaitPhase(kubernetesClient, second, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains(
                    "already has a grant on this bucket from BucketAccess %s/%s".formatted(
                            first.getMetadata().getNamespace(),
                            first.getMetadata().getName()
                    )
            );

            // Nothing recorded, so deleting the refused one cannot revoke the first one's grant.
            assertThat(refused.getStatus().getBucketId()).isNull();
            assertThat(refused.getStatus().getAccessKeyId()).isNull();
        }

        @Test
        @DisplayName("Deleting a refused second BucketAccess should leave the first one's grant intact")
        void refusedGrantDeletion_keepsFirstGrant() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-contested-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-contested-key");

            var first = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            var granted = awaitPhase(kubernetesClient, first, CRPhase.READY);

            var second = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            awaitPhase(kubernetesClient, second, CRPhase.ERROR);

            var resource = kubernetesClient.resources(BucketAccess.class)
                    .inNamespace(second.getMetadata().getNamespace())
                    .withName(second.getMetadata().getName());

            // when
            resource.delete();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> resource.get() == null);

            // then
            var permissions = backendPermissions(granted);

            assertThat(permissions.read()).isTrue();
            assertThat(permissions.write()).isTrue();

            var remaining = kubernetesClient.resources(BucketAccess.class)
                    .inNamespace(first.getMetadata().getNamespace())
                    .withName(first.getMetadata().getName())
                    .get();

            assertThat(remaining.getStatus().getPhase()).isEqualTo(CRPhase.READY);
        }
    }

    @Nested
    class EndToEnd {
        @Test
        @DisplayName("The credentials in the AccessKey's Secret should be able to write and read the bucket")
        void readWriteGrant_worksOverS3() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-e2e-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-e2e-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            try (var s3 = s3ClientFor(accessKeyRef)) {
                // when
                s3.putObject(
                        request -> request.bucket("test-e2e-bucket").key("hello.txt"),
                        RequestBody.fromString("hello from the garage-operator")
                );

                var content = s3.getObjectAsBytes(
                        request -> request.bucket("test-e2e-bucket").key("hello.txt")
                ).asUtf8String();

                // then
                assertThat(content).isEqualTo("hello from the garage-operator");
            }
        }

        @Test
        @DisplayName("A read-only grant should refuse writes over S3")
        void readOnlyGrant_refusesWrites() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-readonly-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-readonly-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            try (var s3 = s3ClientFor(accessKeyRef)) {
                // precondition: the credentials work, so the refusal is down to the grant
                // Listing is a read, so it is allowed.
                assertThat(s3.listObjectsV2(request -> request.bucket("test-readonly-bucket")).contents())
                        .isEmpty();

                // when
                var thrown = catchThrowable(() -> s3.putObject(
                        request -> request.bucket("test-readonly-bucket").key("denied.txt"),
                        RequestBody.fromString("should not land")
                ));

                // then
                assertThat(thrown)
                        .isInstanceOf(S3Exception.class)
                        .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(403));
            }
        }

        @Test
        @DisplayName("Deleting the BucketAccess should revoke the credentials' access over S3")
        void deletedGrant_revokesAccess() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-revoked-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-revoked-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var reconciled = awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            // when
            TestUtil.deleteResource(kubernetesClient, BucketAccess.class);

            // then
            assertThat(backendPermissions(reconciled).read()).isFalse();

            try (var s3 = s3ClientFor(accessKeyRef)) {
                // when
                var thrown = catchThrowable(() -> s3.listObjectsV2(request -> request.bucket("test-revoked-bucket")));

                // then
                assertThat(thrown)
                        .isInstanceOf(S3Exception.class)
                        .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(403));
            }
        }
    }

    @Nested
    class Dependencies {
        @Test
        @DisplayName("A grant waiting on its AccessKey should proceed as soon as the key is ready, not on the next poll")
        void pendingGrant_reactsToAccessKey() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-waiting-bucket");

            // The AccessKey does not exist yet.
            var accessKeyRef = ref("test-late-access-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var pending = awaitPhase(kubernetesClient, bucketAccess, CRPhase.PENDING);

            // precondition: the grant is waiting on the missing AccessKey, not on something else
            assertThat(pending.getStatus().getMessage()).contains("AccessKey does not exist or is not ready yet");

            // when
            var start = System.nanoTime();

            given.one()
                    .accessKey()
                    .withName("test-late-access-key")
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-late-key")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            // Well inside the retry: only the watch on AccessKey can get it there this quickly.
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(RETRY_SECONDS / 2));

            assertThat(backendPermissions(reconciled).read()).isTrue();
        }

        @Test
        @DisplayName("A Bucket and an AccessKey on different S3Connections should be refused")
        void differentConnections_areRefused() {
            // given
            var bucketConnectionRef = given.readyConnection();
            var accessKeyConnectionRef = givenSecondConnection();

            var bucketRef = givenReadyBucket(bucketConnectionRef, "test-split-bucket");
            var accessKeyRef = givenReadyAccessKey(accessKeyConnectionRef, "test-split-key");

            // when
            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            // then
            var reconciled = awaitPhase(kubernetesClient, bucketAccess, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains("live on different S3Connections");
        }

        @Test
        @DisplayName("A grant should still be deletable after its AccessKey is gone")
        void grantOnDeletedAccessKey_isDeletable() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-orphan-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-orphan-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            // Deleting the key revokes its grants on the backend; the BucketAccess must not then
            // get stuck trying to revoke something that is already gone.
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);

            // when
            TestUtil.deleteResource(kubernetesClient, BucketAccess.class);

            // then
            assertThat(kubernetesClient.resources(BucketAccess.class).list().getItems()).isEmpty();
        }
    }

    @Nested
    class Cleanup {
        @Test
        @DisplayName("A BucketAccess that never granted anything should be released at once")
        void neverGranted_isReleasedAtOnce() {
            // given
            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(ref("does-not-exist"))
                    .withAccessKeyRef(ref("does-not-exist-either"))
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var pending = awaitPhase(kubernetesClient, bucketAccess, CRPhase.PENDING);

            // precondition: with a recorded grant, the cleanup would need its connection instead
            assertThat(pending.getStatus().getBucketId()).isNull();

            var resource = kubernetesClient.resources(BucketAccess.class)
                    .inNamespace(bucketAccess.getMetadata().getNamespace())
                    .withName(bucketAccess.getMetadata().getName());

            // when
            resource.delete();

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> resource.get() == null);
        }

        @Test
        @DisplayName("A BucketAccess whose S3Connection is gone should wait for it, and revoke the grant once it is back")
        void lostConnection_blocksDeletionUntilBack() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-orphaned-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-orphaned-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var granted = awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            var s3Connection = kubernetesClient.resources(S3Connection.class)
                    .inNamespace(connectionRef.getNamespace())
                    .withName(connectionRef.getName());

            s3Connection.delete();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> s3Connection.get() == null);

            var resource = kubernetesClient.resources(BucketAccess.class)
                    .inNamespace(bucketAccess.getMetadata().getNamespace())
                    .withName(bucketAccess.getMetadata().getName());

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
            assertThat(backendPermissions(granted).read()).isTrue();

            // when
            given.one()
                    .s3Connection()
                    .withNamespace(connectionRef.getNamespace())
                    .withName(connectionRef.getName())
                    .returnFirst();

            // then
            // The BucketAccess does not watch its S3Connection, so only the retry of the cleanup notices it.
            await().atMost(RETRY_SECONDS + AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> resource.get() == null);

            assertThat(backendPermissions(granted).read()).isFalse();
        }

        @Test
        @DisplayName("A BucketAccess whose bucket is already gone from the backend should be released")
        void deletedBucket_isReleased() {
            // given
            var connectionRef = given.readyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-vanished-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-vanished-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var granted = awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            // Behind the operator's back, so the grant to revoke has nothing left to be revoked on.
            garageAdminApi().deleteBucket(Objects.requireNonNull(granted.getStatus().getBucketId()));

            var resource = kubernetesClient.resources(BucketAccess.class)
                    .inNamespace(bucketAccess.getMetadata().getNamespace())
                    .withName(bucketAccess.getMetadata().getName());

            // when
            resource.delete();

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> resource.get() == null);
        }
    }

    @Nested
    class Teardown {
        @Test
        @DisplayName("Deleting a namespace whose S3Connection is already gone should not be blocked by its BucketAccesses")
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

            var bucket = given.one()
                    .bucket()
                    .withNamespace(namespace)
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-teardown-bucket")
                    .returnFirst();

            awaitPhase(kubernetesClient, bucket, CRPhase.READY);

            var accessKey = given.one()
                    .accessKey()
                    .withNamespace(namespace)
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-teardown-key")
                    .returnFirst();

            awaitPhase(kubernetesClient, accessKey, CRPhase.READY);

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withNamespace(namespace)
                    .withBucketRef(ref(namespace, bucket.getMetadata().getName()))
                    .withAccessKeyRef(ref(namespace, accessKey.getMetadata().getName()))
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var granted = awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            // when
            // What namespace deletion does to a Garage installed in that namespace: the
            // connection goes away, independently of anything that depends on it. Waiting for it
            // keeps the AccessKey from deleting the key, and the grant with it, on the way out.
            var s3Connection = kubernetesClient.resources(S3Connection.class)
                    .inNamespace(namespace)
                    .withName(connectionRef.getName());

            s3Connection.delete();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> s3Connection.get() == null);

            kubernetesClient.namespaces()
                    .withName(namespace)
                    .delete();

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> kubernetesClient.namespaces().withName(namespace).get() == null);

            // Released without the backend: nothing could revoke the grant without its connection.
            assertThat(backendPermissions(granted).read()).isTrue();
        }
    }

    @Nested
    class Rebinding {
        @Test
        @DisplayName("When the referenced Bucket is recreated for another bucket, the grant should move and the old one be revoked")
        void recreatedBucket_movesGrant() {
            // given
            var connectionRef = given.readyConnection();
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-moving-key");

            var original = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-original-bucket")
                    .withReclaimPolicy(ReclaimPolicy.RETAIN)
                    .returnFirst();

            awaitPhase(kubernetesClient, original, CRPhase.READY);

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(ref(original.getMetadata().getName()))
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            var granted = awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            var originalBucketId = Objects.requireNonNull(granted.getStatus().getBucketId());
            var accessKeyId = Objects.requireNonNull(granted.getStatus().getAccessKeyId());

            // when
            // Retained, so the original bucket stays on the backend — with the grant on it.
            var originalResource = kubernetesClient.resources(Bucket.class)
                    .inNamespace(kubernetesClient.getNamespace())
                    .withName(original.getMetadata().getName());

            originalResource.delete();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> originalResource.get() == null);

            var replacement = given.one()
                    .bucket()
                    .withName(original.getMetadata().getName())
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-replacement-bucket")
                    .returnFirst();

            awaitPhase(kubernetesClient, replacement, CRPhase.READY);

            // then
            //noinspection ConstantConditions
            var moved = kubernetesClient.resources(BucketAccess.class)
                    .inNamespace(bucketAccess.getMetadata().getNamespace())
                    .withName(bucketAccess.getMetadata().getName())
                    .waitUntilCondition(
                            item -> item != null
                                    && item.getStatus() != null
                                    && item.getStatus().getPhase() == CRPhase.READY
                                    && !originalBucketId.equals(item.getStatus().getBucketId()),
                            AWAIT_SECONDS,
                            TimeUnit.SECONDS
                    );

            var movedPermissions = backendPermissions(moved);

            assertThat(movedPermissions.read()).isTrue();
            assertThat(movedPermissions.write()).isTrue();

            assertThat(garageAdminApi().getBucketInfo(originalBucketId).keys())
                    .noneSatisfy(key -> assertThat(key.accessKeyId()).isEqualTo(accessKeyId));
        }

        @Test
        @DisplayName("When the referenced Bucket is recreated on another S3Connection, the old grant should be revoked before the refusal")
        void bucketRecreatedOnOtherConnection_revokesGrant() {
            // given
            var connectionRef = given.readyConnection();
            var otherConnectionRef = givenSecondConnection();
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-stranded-key");

            var original = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-stranded-bucket")
                    .withReclaimPolicy(ReclaimPolicy.RETAIN)
                    .returnFirst();

            awaitPhase(kubernetesClient, original, CRPhase.READY);

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(ref(original.getMetadata().getName()))
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            var granted = awaitPhase(kubernetesClient, bucketAccess, CRPhase.READY);

            var originalBucketId = Objects.requireNonNull(granted.getStatus().getBucketId());
            var accessKeyId = Objects.requireNonNull(granted.getStatus().getAccessKeyId());

            // precondition: the grant is on the backend, so its absence later is down to the revoke
            assertThat(backendPermissions(granted).read()).isTrue();

            // when
            // Retained, and the key stays too, so only the revoke can take the grant off the original bucket.
            var originalResource = kubernetesClient.resources(Bucket.class)
                    .inNamespace(original.getMetadata().getNamespace())
                    .withName(original.getMetadata().getName());

            originalResource.delete();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> originalResource.get() == null);

            var replacement = given.one()
                    .bucket()
                    .withName(original.getMetadata().getName())
                    .withConnectionRef(otherConnectionRef)
                    .withBucketName("test-elsewhere-bucket")
                    .returnFirst();

            awaitPhase(kubernetesClient, replacement, CRPhase.READY);

            // then
            var refused = awaitPhase(kubernetesClient, bucketAccess, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains("live on different S3Connections");
            assertThat(refused.getStatus().getBucketId()).isNull();
            assertThat(refused.getStatus().getAccessKeyId()).isNull();

            assertThat(garageAdminApi().getBucketInfo(originalBucketId).keys())
                    .noneSatisfy(key -> assertThat(key.accessKeyId()).isEqualTo(accessKeyId));
        }
    }

    @Nested
    class Validation {
        @Test
        @DisplayName("A grant without any permission should be rejected by the API server")
        void emptyPermissions_areRejected() {
            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .bucketAccess()
                            .withBucketRef(ref("any-bucket"))
                            .withAccessKeyRef(ref("any-key"))
                            .withPermissions()
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("At least one permission must be granted");
        }

        @Test
        @DisplayName("Changing the bucketRef should be rejected by the API server")
        void changedBucketRef_isRejected() {
            // given
            var accessKeyRef = ref("test-pinned-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(ref("test-pinned-bucket"))
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .bucketAccess()
                            .withName(bucketAccess.getMetadata().getName())
                            .withBucketRef(ref("test-other-bucket"))
                            .withAccessKeyRef(accessKeyRef)
                            .withPermissions(BucketPermission.READ)
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("The bucketRef is immutable");
        }

        @Test
        @DisplayName("Changing the accessKeyRef should be rejected by the API server")
        void changedAccessKeyRef_isRejected() {
            // given
            var bucketRef = ref("test-pinned-bucket");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(ref("test-pinned-key"))
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .bucketAccess()
                            .withName(bucketAccess.getMetadata().getName())
                            .withBucketRef(bucketRef)
                            .withAccessKeyRef(ref("test-other-key"))
                            .withPermissions(BucketPermission.READ)
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("The accessKeyRef is immutable");
        }
    }

    @Nested
    class ServiceLogic {
        private final BucketAccessService bucketAccessService = new BucketAccessService();

        @Test
        @DisplayName("An omitted namespace should resolve against the holder of the reference")
        void omittedNamespace_resolvesAgainstHolder() {
            // given
            var ref = new ResourceRef();

            ref.setName("acme");

            // when
            var qualified = bucketAccessService.qualify(ref, "tenant-a");

            // then
            assertThat(qualified.getNamespace()).isEqualTo("tenant-a");
            assertThat(qualified.getName()).isEqualTo("acme");
        }

        @Test
        @DisplayName("The permission list should be the complete desired state")
        void permissionList_isCompleteState() {
            // when
            var permissions = bucketAccessService.desiredPermissions(
                    List.of(BucketPermission.WRITE)
            );

            // then
            assertThat(permissions.read()).isFalse();
            assertThat(permissions.write()).isTrue();
            assertThat(permissions.owner()).isFalse();
        }
    }
}
