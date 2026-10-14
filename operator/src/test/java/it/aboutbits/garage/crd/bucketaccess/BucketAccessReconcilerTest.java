package it.aboutbits.garage.crd.bucketaccess;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
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

    private ResourceRef ref(String name) {
        var ref = new ResourceRef();

        ref.setNamespace(kubernetesClient.getNamespace());
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
                // Since 2.30 the SDK sends a CRC32 checksum trailer with every upload by default.
                // Over plain HTTP the chunks are also signed, and Garage rejects that combination
                // as an invalid payload signature (seen on v2.3.0 and v2.4.1). Only checksumming
                // when an operation requires it avoids the trailer — the same setting
                // applications need against Garage.
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

            // A second connection to the same Garage, without a second GarageCluster, which the operator refuses.
            var accessKeyConnection = awaitPhase(
                    kubernetesClient,
                    given.one()
                            .s3Connection()
                            .returnFirst(),
                    CRPhase.READY
            );

            var accessKeyConnectionRef = new ResourceRef();

            accessKeyConnectionRef.setNamespace(accessKeyConnection.getMetadata().getNamespace());
            accessKeyConnectionRef.setName(accessKeyConnection.getMetadata().getName());

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

            assertThat(reconciled.getStatus().getMessage()).contains("different S3Connections");
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
