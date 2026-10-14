package it.aboutbits.garage.crd.bucket;

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
import it.aboutbits.garage.core.adminapi.dto.BucketKeyPermChangeRequest;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/// Exercises bucket management against the real Garage node provided by the Compose Dev Service.
///
/// Buckets created here are deleted in `@BeforeEach` through the Admin API directly, because the
/// default `Retain` policy deliberately leaves them behind when the CR goes away.
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@RequiredArgsConstructor
@NullMarked
class BucketReconcilerTest {
    private static final long AWAIT_SECONDS = 60;

    /// Must match `s3_region` in the Dev Service's garage.toml.
    private static final Region GARAGE_REGION = Region.of("garage");

    private final KubernetesClient kubernetesClient;

    private final Given given;

    private final GarageAdminClientFactory garageAdminClientFactory;

    @ConfigProperty(name = "garage.admin.port")
    private final int garageAdminPort;

    @ConfigProperty(name = "garage.admin.token")
    private final String garageAdminToken;

    @ConfigProperty(name = "garage.s3.port")
    private final int garageS3Port;

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

        // Retained buckets outlive their CR, so clear them out directly.
        //
        // This fails while no layout has been applied to the shared Garage node, because bucket
        // calls need a serving cluster. That state has no buckets to clean up either, so it is
        // ignored rather than ordered around.
        try {
            garageAdminApi().listBuckets().forEach(bucket -> garageAdminApi().deleteBucket(bucket.id()));
        } catch (RuntimeException e) {
            log.debug("Skipping bucket cleanup, the Garage cluster is not serving yet", e);
        }
    }

    private Bucket awaitPhase(
            Bucket bucket,
            CRPhase expectedPhase
    ) {
        //noinspection ConstantConditions
        return kubernetesClient.resources(Bucket.class)
                .inNamespace(bucket.getMetadata().getNamespace())
                .withName(bucket.getMetadata().getName())
                .waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getPhase() == expectedPhase,
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

    /// Apply a cluster layout, without which Garage serves no bucket calls at all.
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

    /// Bring the cluster up and return a ready connection that buckets can reference.
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

    private S3Client s3Client(
            String accessKeyId,
            String secretAccessKey
    ) {
        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:%d".formatted(garageS3Port)))
                .region(GARAGE_REGION)
                .forcePathStyle(true)
                // See BucketAccessReconcilerTest: Garage rejects the signed checksum trailer the SDK
                // sends over plain HTTP by default.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        accessKeyId,
                        secretAccessKey
                )))
                .build();
    }

    private boolean bucketExists(String name) {
        return garageAdminApi().listBuckets().stream()
                .anyMatch(bucket -> bucket.globalAliases().contains(name));
    }

    @Nested
    class Create {
        @Test
        @DisplayName("A Bucket should be created on the backend")
        void bucket_isCreated() {
            // given
            var connectionRef = givenReadyConnection();

            // when
            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-create-bucket")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(bucket, CRPhase.READY);

            assertThat(reconciled.getStatus().getBucketId()).isNotBlank();
            assertThat(reconciled.getStatus().getObjects()).isZero();

            assertThat(bucketExists("test-create-bucket")).isTrue();
        }

        @Test
        @DisplayName("A Bucket with quotas should have them applied on the backend")
        void quotas_areApplied() {
            // given
            var connectionRef = givenReadyConnection();

            // when
            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-quota-bucket")
                    .withMaxSize("1Gi")
                    .withMaxObjects(500L)
                    .returnFirst();

            // then
            awaitPhase(bucket, CRPhase.READY);

            var bucketId = garageAdminApi().listBuckets().stream()
                    .filter(item -> item.globalAliases().contains("test-quota-bucket"))
                    .findFirst()
                    .orElseThrow()
                    .id();

            var info = garageAdminApi().getBucketInfo(bucketId);

            assertThat(info.quotas().maxSize()).isEqualTo(1073741824L);
            assertThat(info.quotas().maxObjects()).isEqualTo(500L);
        }

        @Test
        @DisplayName("An existing bucket should be adopted rather than reported as an error")
        void existingBucket_isAdopted() {
            // given
            var connectionRef = givenReadyConnection();

            // Someone created it out of band, before the CR existed.
            garageAdminApi().createBucket(
                    new it.aboutbits.garage.core.adminapi.dto.CreateBucketRequest("test-adopted-bucket")
            );

            // when
            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-adopted-bucket")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(bucket, CRPhase.READY);

            assertThat(reconciled.getStatus().getBucketId()).isNotBlank();
            assertThat(garageAdminApi().listBuckets()).hasSize(1);
        }
    }

    @Nested
    class Update {
        @Test
        @DisplayName("Changing the quotas should update them on the backend")
        void changedQuotas_areUpdated() {
            // given
            var connectionRef = givenReadyConnection();

            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-requota-bucket")
                    .withMaxObjects(100L)
                    .returnFirst();

            awaitPhase(bucket, CRPhase.READY);

            // when
            var updated = given.one()
                    .bucket()
                    .withName(bucket.getMetadata().getName())
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-requota-bucket")
                    .withMaxObjects(250L)
                    .returnFirst();

            // then
            awaitPhase(updated, CRPhase.READY);

            var bucketId = garageAdminApi().listBuckets().stream()
                    .filter(item -> item.globalAliases().contains("test-requota-bucket"))
                    .findFirst()
                    .orElseThrow()
                    .id();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(
                            garageAdminApi().getBucketInfo(bucketId).quotas().maxObjects()
                    ).isEqualTo(250L));
        }

        @Test
        @DisplayName("Removing the quotas should lift the limits on the backend")
        void removedQuotas_areLifted() {
            // given
            var connectionRef = givenReadyConnection();

            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-unquota-bucket")
                    .withMaxObjects(100L)
                    .returnFirst();

            awaitPhase(bucket, CRPhase.READY);

            // when
            var updated = given.one()
                    .bucket()
                    .withName(bucket.getMetadata().getName())
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-unquota-bucket")
                    .withoutQuotas()
                    .returnFirst();

            // then
            awaitPhase(updated, CRPhase.READY);

            var bucketId = garageAdminApi().listBuckets().stream()
                    .filter(item -> item.globalAliases().contains("test-unquota-bucket"))
                    .findFirst()
                    .orElseThrow()
                    .id();

            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(
                            garageAdminApi().getBucketInfo(bucketId).quotas().maxObjects()
                    ).isNull());
        }
    }

    @Nested
    class Delete {
        @Test
        @DisplayName("A Retain Bucket should leave the bucket behind when the CR is deleted")
        void retainPolicy_keepsBucket() {
            // given
            var connectionRef = givenReadyConnection();

            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-retained-bucket")
                    .withReclaimPolicy(ReclaimPolicy.RETAIN)
                    .returnFirst();

            awaitPhase(bucket, CRPhase.READY);

            // when
            TestUtil.deleteResource(kubernetesClient, Bucket.class);

            // then
            assertThat(bucketExists("test-retained-bucket")).isTrue();
        }

        @Test
        @DisplayName("A Delete Bucket should remove the bucket when the CR is deleted")
        void deletePolicy_removesBucket() {
            // given
            var connectionRef = givenReadyConnection();

            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-deleted-bucket")
                    .withReclaimPolicy(ReclaimPolicy.DELETE)
                    .returnFirst();

            awaitPhase(bucket, CRPhase.READY);

            // when
            TestUtil.deleteResource(kubernetesClient, Bucket.class);

            // then
            assertThat(bucketExists("test-deleted-bucket")).isFalse();
        }

        @Test
        @DisplayName("A Delete Bucket that still holds objects should be kept, and released once switched to Retain")
        void nonEmptyBucket_isKept() {
            // given
            var connectionRef = givenReadyConnection();

            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-full-bucket")
                    .withReclaimPolicy(ReclaimPolicy.DELETE)
                    .returnFirst();

            var bucketId = Objects.requireNonNull(awaitPhase(bucket, CRPhase.READY).getStatus().getBucketId());

            // A key of its own, so the bucket can be filled over S3.
            var writerKey = garageAdminApi().createKey(UpdateKeyRequestBody.named("test-writer-key"));

            garageAdminApi().allowBucketKey(new BucketKeyPermChangeRequest(
                    bucketId,
                    writerKey.accessKeyId(),
                    new ApiBucketKeyPerm(true, true, false)
            ));

            try (var s3Client = s3Client(writerKey.accessKeyId(), Objects.requireNonNull(writerKey.secretAccessKey()))) {
                s3Client.putObject(
                        PutObjectRequest.builder().bucket("test-full-bucket").key("keep-me.txt").build(),
                        RequestBody.fromString("still here")
                );

                var resource = kubernetesClient.resources(Bucket.class)
                        .inNamespace(bucket.getMetadata().getNamespace())
                        .withName(bucket.getMetadata().getName());

                // when
                resource.delete();

                // then
                //noinspection ConstantConditions
                var blocked = resource.waitUntilCondition(
                        item -> item != null
                                && item.getStatus() != null
                                && item.getStatus().getMessage() != null
                                && item.getStatus().getMessage().contains("still holds objects"),
                        AWAIT_SECONDS,
                        TimeUnit.SECONDS
                );

                assertThat(blocked.getStatus().getPhase()).isEqualTo(CRPhase.DELETING);
                assertThat(bucketExists("test-full-bucket")).isTrue();

                // when
                // Switching to Retain releases the CR without touching the bucket.
                resource.edit(item -> {
                    item.getSpec().setReclaimPolicy(ReclaimPolicy.RETAIN);

                    return item;
                });

                // then
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                        .pollInterval(500, TimeUnit.MILLISECONDS)
                        .until(() -> resource.get() == null);

                assertThat(bucketExists("test-full-bucket")).isTrue();

                // Emptied again, so the next test can clean the bucket up.
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket("test-full-bucket").key("keep-me.txt").build());
            } finally {
                garageAdminApi().deleteKey(writerKey.accessKeyId());
            }
        }
    }

    @Nested
    class Gating {
        @Test
        @DisplayName("A Bucket referencing an unknown S3Connection should stay pending")
        void unknownConnection_staysPending() {
            // given
            givenAppliedLayout();

            var connectionRef = new ResourceRef();

            connectionRef.setName("does-not-exist");
            connectionRef.setNamespace(kubernetesClient.getNamespace());

            // when
            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-ungated-bucket")
                    .returnFirst();

            // then
            var reconciled = awaitPhase(bucket, CRPhase.PENDING);

            assertThat(reconciled.getStatus().getMessage()).contains("does not exist or is not ready yet");
            assertThat(garageAdminApi().listBuckets()).isEmpty();
        }

        @Test
        @DisplayName("A Bucket referencing a failed S3Connection should stay pending")
        void failedConnection_staysPending() {
            // given
            givenAppliedLayout();

            var adminSecretRef = given.one()
                    .secretKeyRef()
                    .withToken("definitely-not-the-admin-token")
                    .returnFirst();

            var s3Connection = given.one()
                    .s3Connection()
                    .withAdminSecretRef(adminSecretRef)
                    .returnFirst();

            //noinspection ConstantConditions
            kubernetesClient.resources(S3Connection.class)
                    .inNamespace(s3Connection.getMetadata().getNamespace())
                    .withName(s3Connection.getMetadata().getName())
                    .waitUntilCondition(
                            item -> item != null
                                    && item.getStatus() != null
                                    && item.getStatus().getPhase() == CRPhase.ERROR,
                            AWAIT_SECONDS,
                            TimeUnit.SECONDS
                    );

            var connectionRef = new ResourceRef();

            connectionRef.setName(s3Connection.getMetadata().getName());
            connectionRef.setNamespace(s3Connection.getMetadata().getNamespace());

            // when
            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-gated-bucket")
                    .returnFirst();

            // then
            awaitPhase(bucket, CRPhase.PENDING);

            assertThat(garageAdminApi().listBuckets()).isEmpty();
        }
    }

    @Nested
    class Ownership {
        @Test
        @DisplayName("A second Bucket for a bucket that is already managed should be refused, and deleting it should leave the bucket alone")
        void secondBucketForManagedBucket_isRefused() {
            // given
            var connectionRef = givenReadyConnection();

            var first = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-shared-bucket")
                    .returnFirst();

            awaitPhase(first, CRPhase.READY);

            // when
            var second = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-shared-bucket")
                    .withReclaimPolicy(ReclaimPolicy.DELETE)
                    .returnFirst();

            // then
            var refused = awaitPhase(second, CRPhase.ERROR);

            assertThat(refused.getStatus().getMessage()).contains("already managed by Bucket");
            assertThat(refused.getStatus().getBucketId()).isNull();

            // when
            var resource = kubernetesClient.resources(Bucket.class)
                    .inNamespace(second.getMetadata().getNamespace())
                    .withName(second.getMetadata().getName());

            resource.delete();

            // then
            await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> resource.get() == null);

            assertThat(bucketExists("test-shared-bucket")).isTrue();
        }
    }

    @Nested
    class Immutability {
        @Test
        @DisplayName("Changing the connectionRef should be rejected by the API server")
        void changedConnectionRef_isRejected() {
            // given
            var connectionRef = givenReadyConnection();

            var bucket = given.one()
                    .bucket()
                    .withConnectionRef(connectionRef)
                    .withBucketName("test-pinned-bucket")
                    .returnFirst();

            awaitPhase(bucket, CRPhase.READY);

            var otherConnectionRef = new ResourceRef();

            otherConnectionRef.setName("another-connection");
            otherConnectionRef.setNamespace(connectionRef.getNamespace());

            // when
            var thrown = catchThrowable(
                    () -> given.one()
                            .bucket()
                            .withName(bucket.getMetadata().getName())
                            .withConnectionRef(otherConnectionRef)
                            .withBucketName("test-pinned-bucket")
                            .returnFirst()
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("connectionRef is immutable");
        }
    }

    @Nested
    class Naming {
        @Test
        @DisplayName("An invalid bucket name should be rejected by the API server")
        void invalidName_isRejected() {
            // given
            var connectionRef = givenReadyConnection();

            assertThat(List.of("UPPERCASE", "ab", "-leading-hyphen", "trailing-hyphen-"))
                    .allSatisfy(invalidName -> {
                        // when
                        var thrown = catchThrowable(
                                () -> given.one()
                                        .bucket()
                                        .withConnectionRef(connectionRef)
                                        .withBucketName(invalidName)
                                        .returnFirst()
                        );

                        // then
                        assertThat(thrown)
                                .as("Expected the bucket name to be rejected: %s", invalidName)
                                .isInstanceOf(RuntimeException.class)
                                .hasMessageContaining("spec.name");
                    });
        }
    }

    @Nested
    class Quotas {
        @Test
        @DisplayName("A maxSize that is not a whole number of bytes greater than zero should be rejected by the API server")
        void invalidMaxSize_isRejected() {
            // given
            var connectionRef = givenReadyConnection();

            assertThat(List.of("", "0", "-1Gi", "0.5", "1m", "10 Gi", "ten"))
                    .allSatisfy(invalidMaxSize -> {
                        // when
                        var thrown = catchThrowable(
                                () -> given.one()
                                        .bucket()
                                        .withConnectionRef(connectionRef)
                                        .withMaxSize(invalidMaxSize)
                                        .returnFirst()
                        );

                        // then
                        assertThat(thrown)
                                .as("Expected the maxSize to be rejected: %s", invalidMaxSize)
                                .isInstanceOf(RuntimeException.class)
                                .hasMessageContaining("The maxSize must be a Kubernetes quantity of whole bytes");
                    });
        }
    }
}
