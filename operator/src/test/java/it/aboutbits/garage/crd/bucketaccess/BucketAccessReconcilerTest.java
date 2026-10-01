package it.aboutbits.garage.crd.bucketaccess;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import it.aboutbits.garage._support.testdata.base.TestUtil;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.CRStatus;
import it.aboutbits.garage.core.ReclaimPolicy;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
import it.aboutbits.garage.core.adminapi.dto.ApiBucketKeyPerm;
import it.aboutbits.garage.core.adminapi.dto.GetBucketInfoKey;
import it.aboutbits.garage.crd.accesskey.AccessKey;
import it.aboutbits.garage.crd.bucket.Bucket;
import it.aboutbits.garage.crd.garagecluster.GarageCluster;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static it.aboutbits.garage.crd.accesskey.AccessKeyReconciler.SECRET_DATA_ACCESS_KEY_ID;
import static it.aboutbits.garage.crd.accesskey.AccessKeyReconciler.SECRET_DATA_SECRET_ACCESS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// Exercises grants against the real Garage node provided by the Compose Dev Service.
///
/// Besides checking what the Admin API reports, the end-to-end tests use the credentials the
/// operator wrote into the `AccessKey`'s Secret to talk to Garage's S3 API — which is the only
/// check that the whole chain, from resources to a working client, actually holds together.
@Slf4j
@QuarkusTest
@NullMarked
class BucketAccessReconcilerTest {
    private static final long AWAIT_SECONDS = 60;

    /// Must match `s3_region` in the Dev Service's garage.toml.
    private static final Region GARAGE_REGION = Region.of("garage");

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

    @ConfigProperty(name = "garage.s3.port")
    @SuppressWarnings("NullAway.Init")
    Integer garageS3Port;

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

        // Retained buckets and leftover keys outlive their resources. Bucket and key calls need a
        // serving cluster; before any layout is applied there is nothing to clean up anyway.
        try {
            var garageAdminApi = garageAdminApi();

            garageAdminApi.listKeys().forEach(key -> garageAdminApi.deleteKey(key.id()));
            garageAdminApi.listBuckets().forEach(bucket -> garageAdminApi.deleteBucket(bucket.id()));
        } catch (RuntimeException e) {
            log.debug("Skipping backend cleanup, the Garage cluster is not serving yet", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private void givenAppliedLayout() {
        var garageCluster = given.one()
                .garageCluster()
                .returnFirst();

        awaitReady(GarageCluster.class, garageCluster.getMetadata().getName());
    }

    private ResourceRef givenReadyConnection() {
        var s3Connection = given.one()
                .s3Connection()
                .returnFirst();

        awaitReady(S3Connection.class, s3Connection.getMetadata().getName());

        return ref(s3Connection.getMetadata().getName());
    }

    private ResourceRef givenReadyBucket(
            ResourceRef connectionRef,
            String bucketName
    ) {
        var bucket = given.one()
                .bucket()
                .withConnectionRef(connectionRef)
                .withBucketName(bucketName)
                .returnFirst();

        awaitReady(Bucket.class, bucket.getMetadata().getName());

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

        awaitReady(AccessKey.class, accessKey.getMetadata().getName());

        return ref(accessKey.getMetadata().getName());
    }

    private ResourceRef ref(String name) {
        var ref = new ResourceRef();

        ref.setName(name);
        ref.setNamespace(kubernetesClient.getNamespace());

        return ref;
    }

    private <T extends CustomResource<?, ? extends CRStatus>> void awaitReady(
            Class<T> type,
            String name
    ) {
        kubernetesClient.resources(type)
                .inNamespace(kubernetesClient.getNamespace())
                .withName(name)
                .waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getPhase() == CRPhase.READY,
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

    private BucketAccess awaitPhase(
            BucketAccess bucketAccess,
            CRPhase expectedPhase,
            long seconds
    ) {
        //noinspection ConstantConditions
        return kubernetesClient.resources(BucketAccess.class)
                .inNamespace(bucketAccess.getMetadata().getNamespace())
                .withName(bucketAccess.getMetadata().getName())
                .waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getPhase() == expectedPhase,
                        seconds,
                        TimeUnit.SECONDS
                );
    }

    private BucketAccess awaitPhase(
            BucketAccess bucketAccess,
            CRPhase expectedPhase
    ) {
        return awaitPhase(bucketAccess, expectedPhase, AWAIT_SECONDS);
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
            givenAppliedLayout();
            var connectionRef = givenReadyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-grant-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-grant-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            var reconciled = awaitPhase(bucketAccess, CRPhase.READY);

            var permissions = backendPermissions(reconciled);

            assertThat(permissions.read()).isTrue();
            assertThat(permissions.write()).isTrue();
            assertThat(permissions.owner()).isFalse();
        }

        @Test
        @DisplayName("Removing a permission from the list should revoke it on the backend")
        void droppedPermission_isRevoked() {
            givenAppliedLayout();
            var connectionRef = givenReadyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-downgrade-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-downgrade-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE, BucketPermission.OWNER)
                    .returnFirst();

            awaitPhase(bucketAccess, CRPhase.READY);

            given.one()
                    .bucketAccess()
                    .withName(bucketAccess.getMetadata().getName())
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        var permissions = backendPermissions(awaitPhase(bucketAccess, CRPhase.READY));

                        assertThat(permissions.read()).isTrue();
                        assertThat(permissions.write()).isFalse();
                        assertThat(permissions.owner()).isFalse();
                    });
        }
    }

    @Nested
    class EndToEnd {
        @Test
        @DisplayName("The credentials in the AccessKey's Secret should be able to write and read the bucket")
        void readWriteGrant_worksOverS3() {
            givenAppliedLayout();
            var connectionRef = givenReadyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-e2e-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-e2e-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            awaitPhase(bucketAccess, CRPhase.READY);

            try (var s3 = s3ClientFor(accessKeyRef)) {
                s3.putObject(
                        request -> request.bucket("test-e2e-bucket").key("hello.txt"),
                        RequestBody.fromString("hello from the garage-operator")
                );

                var content = s3.getObjectAsBytes(
                        request -> request.bucket("test-e2e-bucket").key("hello.txt")
                ).asUtf8String();

                assertThat(content).isEqualTo("hello from the garage-operator");
            }
        }

        @Test
        @DisplayName("A read-only grant should refuse writes over S3")
        void readOnlyGrant_refusesWrites() {
            givenAppliedLayout();
            var connectionRef = givenReadyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-readonly-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-readonly-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            awaitPhase(bucketAccess, CRPhase.READY);

            try (var s3 = s3ClientFor(accessKeyRef)) {
                // Listing is a read, so it is allowed.
                assertThat(s3.listObjectsV2(request -> request.bucket("test-readonly-bucket")).contents())
                        .isEmpty();

                assertThatThrownBy(() -> s3.putObject(
                        request -> request.bucket("test-readonly-bucket").key("denied.txt"),
                        RequestBody.fromString("should not land")
                ))
                        .isInstanceOf(S3Exception.class)
                        .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(403));
            }
        }

        @Test
        @DisplayName("Deleting the BucketAccess should revoke the credentials' access over S3")
        void deletedGrant_revokesAccess() {
            givenAppliedLayout();
            var connectionRef = givenReadyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-revoked-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-revoked-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var reconciled = awaitPhase(bucketAccess, CRPhase.READY);

            TestUtil.deleteResource(kubernetesClient, BucketAccess.class);

            assertThat(backendPermissions(reconciled).read()).isFalse();

            try (var s3 = s3ClientFor(accessKeyRef)) {
                assertThatThrownBy(() -> s3.listObjectsV2(request -> request.bucket("test-revoked-bucket")))
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
            givenAppliedLayout();
            var connectionRef = givenReadyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-waiting-bucket");

            // The AccessKey does not exist yet.
            var accessKeyRef = ref("test-late-access-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var pending = awaitPhase(bucketAccess, CRPhase.PENDING);

            assertThat(pending.getStatus().getMessage()).contains("AccessKey does not exist or is not ready yet");

            given.one()
                    .accessKey()
                    .withName("test-late-access-key")
                    .withConnectionRef(connectionRef)
                    .withKeyName("test-late-key")
                    .returnFirst();

            // Well inside the 60 second retry: only the watch on AccessKey can get it there this
            // quickly.
            var reconciled = awaitPhase(bucketAccess, CRPhase.READY, 30);

            assertThat(backendPermissions(reconciled).read()).isTrue();
        }

        @Test
        @DisplayName("A Bucket and an AccessKey on different S3Connections should be refused")
        void differentConnections_areRefused() {
            givenAppliedLayout();
            var bucketConnectionRef = givenReadyConnection();
            var accessKeyConnectionRef = givenReadyConnection();

            var bucketRef = givenReadyBucket(bucketConnectionRef, "test-split-bucket");
            var accessKeyRef = givenReadyAccessKey(accessKeyConnectionRef, "test-split-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            var reconciled = awaitPhase(bucketAccess, CRPhase.ERROR);

            assertThat(reconciled.getStatus().getMessage()).contains("different S3Connections");
        }

        @Test
        @DisplayName("A grant should still be deletable after its AccessKey is gone")
        void grantOnDeletedAccessKey_isDeletable() {
            givenAppliedLayout();
            var connectionRef = givenReadyConnection();
            var bucketRef = givenReadyBucket(connectionRef, "test-orphan-bucket");
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-orphan-key");

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(bucketRef)
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ)
                    .returnFirst();

            awaitPhase(bucketAccess, CRPhase.READY);

            // Deleting the key revokes its grants on the backend; the BucketAccess must not then
            // get stuck trying to revoke something that is already gone.
            TestUtil.deleteResource(kubernetesClient, AccessKey.class);
            TestUtil.deleteResource(kubernetesClient, BucketAccess.class);

            assertThat(kubernetesClient.resources(BucketAccess.class).list().getItems()).isEmpty();
        }
    }

    @Nested
    class Rebinding {
        @Test
        @DisplayName("When the referenced Bucket is recreated for another bucket, the grant should move and the old one be revoked")
        void recreatedBucket_movesGrant() {
            givenAppliedLayout();
            var connectionRef = givenReadyConnection();
            var accessKeyRef = givenReadyAccessKey(connectionRef, "test-moving-key");

            var original = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-original-bucket")
                    .withReclaimPolicy(ReclaimPolicy.RETAIN)
                    .returnFirst();

            awaitReady(Bucket.class, original.getMetadata().getName());

            var bucketAccess = given.one()
                    .bucketAccess()
                    .withBucketRef(ref(original.getMetadata().getName()))
                    .withAccessKeyRef(accessKeyRef)
                    .withPermissions(BucketPermission.READ, BucketPermission.WRITE)
                    .returnFirst();

            var granted = awaitPhase(bucketAccess, CRPhase.READY);

            var originalBucketId = Objects.requireNonNull(granted.getStatus().getBucketId());
            var accessKeyId = Objects.requireNonNull(granted.getStatus().getAccessKeyId());

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

            awaitReady(Bucket.class, replacement.getMetadata().getName());

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
            assertThatThrownBy(() -> given.one()
                    .bucketAccess()
                    .withBucketRef(ref("any-bucket"))
                    .withAccessKeyRef(ref("any-key"))
                    .withPermissions()
                    .returnFirst()
            ).hasMessageContaining("At least one permission must be granted");
        }
    }

    @Nested
    class ServiceLogic {
        private final BucketAccessService bucketAccessService = new BucketAccessService();

        @Test
        @DisplayName("An omitted namespace should resolve against the holder of the reference")
        void omittedNamespace_resolvesAgainstHolder() {
            var ref = new ResourceRef();

            ref.setName("acme");

            var qualified = bucketAccessService.qualify(ref, "tenant-a");

            assertThat(qualified.getNamespace()).isEqualTo("tenant-a");
            assertThat(qualified.getName()).isEqualTo("acme");
        }

        @Test
        @DisplayName("The permission list should be the complete desired state")
        void permissionList_isCompleteState() {
            var permissions = bucketAccessService.desiredPermissions(
                    List.of(BucketPermission.WRITE)
            );

            assertThat(permissions.read()).isFalse();
            assertThat(permissions.write()).isTrue();
            assertThat(permissions.owner()).isFalse();
        }
    }
}
