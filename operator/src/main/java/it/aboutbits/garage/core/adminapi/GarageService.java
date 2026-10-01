package it.aboutbits.garage.core.adminapi;

import it.aboutbits.garage.core.adminapi.dto.ApiBucketKeyPerm;
import it.aboutbits.garage.core.adminapi.dto.ApiBucketQuotas;
import it.aboutbits.garage.core.adminapi.dto.BucketKeyPermChangeRequest;
import it.aboutbits.garage.core.adminapi.dto.CreateBucketRequest;
import it.aboutbits.garage.core.adminapi.dto.GetBucketInfoResponse;
import it.aboutbits.garage.core.adminapi.dto.GetKeyInfoResponse;
import it.aboutbits.garage.core.adminapi.dto.ListKeysResponseItem;
import it.aboutbits.garage.core.adminapi.dto.UpdateBucketRequestBody;
import it.aboutbits.garage.core.adminapi.dto.UpdateKeyRequestBody;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;

import java.util.Optional;
import java.util.stream.Collectors;

/// Drives Garage through its Admin API v2.
@ApplicationScoped
@RequiredArgsConstructor
@NullMarked
public class GarageService {
    /// Includes a node that has no cluster layout applied yet.
    static final String HEALTH_STATUS_UNAVAILABLE = "unavailable";

    private final GarageAdminClientFactory garageAdminClientFactory;

    public ConnectionProbeResult probe(S3Connection s3Connection) {
        var health = garageAdminClientFactory.create(s3Connection)
                .getClusterHealth();

        var detail = "Garage cluster %s [storageNodes=%d, storageNodesUp=%d, partitionsAllOk=%d/%d]".formatted(
                health.status(),
                health.storageNodes(),
                health.storageNodesUp(),
                health.partitionsAllOk(),
                health.partitions()
        );

        // `degraded` still serves reads and writes, so only `unavailable` counts as not ready.
        if (HEALTH_STATUS_UNAVAILABLE.equals(health.status())) {
            return ConnectionProbeResult.unavailable(
                    "%s. The cluster cannot serve requests — check that a GarageCluster has applied a layout.".formatted(detail)
            );
        }

        return ConnectionProbeResult.available(detail);
    }

    public Optional<BucketInfo> findBucket(
            S3Connection s3Connection,
            String name
    ) {
        var garageAdminApi = garageAdminClientFactory.create(s3Connection);

        return garageAdminApi.listBuckets().stream()
                .filter(bucket -> bucket.globalAliases().contains(name))
                .findFirst()
                .map(bucket -> toBucketInfo(
                        garageAdminApi.getBucketInfo(bucket.id()),
                        name
                ));
    }

    public Optional<BucketInfo> getBucket(
            S3Connection s3Connection,
            String bucketId
    ) {
        var garageAdminApi = garageAdminClientFactory.create(s3Connection);

        // Checked through the listing rather than reading a failed GetBucketInfo as "gone".
        var bucketExists = garageAdminApi.listBuckets().stream()
                .anyMatch(bucket -> bucket.id().equals(bucketId));

        if (!bucketExists) {
            return Optional.empty();
        }

        var bucketInfo = garageAdminApi.getBucketInfo(bucketId);

        return Optional.of(toBucketInfo(bucketInfo, primaryName(bucketInfo)));
    }

    public BucketInfo createBucket(
            S3Connection s3Connection,
            String name
    ) {
        var created = garageAdminClientFactory.create(s3Connection)
                .createBucket(new CreateBucketRequest(name));

        return toBucketInfo(created, name);
    }

    public BucketInfo updateBucketQuotas(
            S3Connection s3Connection,
            String bucketId,
            BucketQuotas quotas
    ) {
        var updated = garageAdminClientFactory.create(s3Connection)
                .updateBucket(
                        bucketId,
                        new UpdateBucketRequestBody(
                                new ApiBucketQuotas(
                                        quotas.maxSizeBytes(),
                                        quotas.maxObjects()
                                )
                        )
                );

        return toBucketInfo(updated, primaryName(updated));
    }

    public void deleteBucket(
            S3Connection s3Connection,
            String bucketId
    ) {
        try {
            garageAdminClientFactory.create(s3Connection)
                    .deleteBucket(bucketId);
        } catch (WebApplicationException e) {
            // Garage answers `BucketNotEmpty` with 409.
            if (e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                throw new BucketNotEmptyException(bucketId);
            }

            throw e;
        }
    }

    public Optional<AccessKeyInfo> findAccessKey(
            S3Connection s3Connection,
            String name
    ) {
        var garageAdminApi = garageAdminClientFactory.create(s3Connection);

        // Garage does not enforce unique key names.
        var matches = garageAdminApi.listKeys().stream()
                .filter(key -> name.equals(key.name()))
                .toList();

        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "More than one access key is named %s on the backend, so it is ambiguous which one to manage. Delete or rename the extra keys [accessKey.ids=%s]".formatted(
                            name,
                            matches.stream()
                                    .map(ListKeysResponseItem::id)
                                    .collect(Collectors.joining(", "))
                    )
            );
        }

        return matches.stream()
                .findFirst()
                .map(key -> toAccessKeyInfo(garageAdminApi.getKeyInfo(key.id(), true)));
    }

    public Optional<AccessKeyInfo> getAccessKey(
            S3Connection s3Connection,
            String accessKeyId
    ) {
        var garageAdminApi = garageAdminClientFactory.create(s3Connection);

        var keyExists = garageAdminApi.listKeys().stream()
                .anyMatch(key -> key.id().equals(accessKeyId));

        if (!keyExists) {
            return Optional.empty();
        }

        return Optional.of(toAccessKeyInfo(garageAdminApi.getKeyInfo(accessKeyId, true)));
    }

    public AccessKeyInfo createAccessKey(
            S3Connection s3Connection,
            String name,
            AccessKeyPermissions permissions
    ) {
        var created = garageAdminClientFactory.create(s3Connection)
                .createKey(UpdateKeyRequestBody.createKey(
                        name,
                        permissions.allowCreateBucket()
                ));

        return toAccessKeyInfo(created);
    }

    public AccessKeyInfo updateAccessKeyPermissions(
            S3Connection s3Connection,
            String accessKeyId,
            AccessKeyPermissions permissions
    ) {
        var garageAdminApi = garageAdminClientFactory.create(s3Connection);

        garageAdminApi.updateKey(
                accessKeyId,
                UpdateKeyRequestBody.permissions(permissions.allowCreateBucket())
        );

        // The UpdateKey response omits the secret, so read the key back.
        return toAccessKeyInfo(garageAdminApi.getKeyInfo(accessKeyId, true));
    }

    public void deleteAccessKey(
            S3Connection s3Connection,
            String accessKeyId
    ) {
        garageAdminClientFactory.create(s3Connection)
                .deleteKey(accessKeyId);
    }

    public BucketPermissions getBucketKeyPermissions(
            S3Connection s3Connection,
            String bucketId,
            String accessKeyId
    ) {
        var bucketInfo = garageAdminClientFactory.create(s3Connection)
                .getBucketInfo(bucketId);

        return permissionsOf(bucketInfo, accessKeyId);
    }

    public BucketPermissions setBucketKeyPermissions(
            S3Connection s3Connection,
            String bucketId,
            String accessKeyId,
            BucketPermissions permissions
    ) {
        var garageAdminApi = garageAdminClientFactory.create(s3Connection);

        // Allow and deny are complements, so one call of each reaches the desired state without a diff.
        var toAllow = new ApiBucketKeyPerm(
                permissions.read(),
                permissions.write(),
                permissions.owner()
        );
        var toDeny = new ApiBucketKeyPerm(
                !permissions.read(),
                !permissions.write(),
                !permissions.owner()
        );

        if (toDeny.isEmpty()) {
            return permissionsOf(
                    garageAdminApi.allowBucketKey(new BucketKeyPermChangeRequest(bucketId, accessKeyId, toAllow)),
                    accessKeyId
            );
        }

        if (!toAllow.isEmpty()) {
            garageAdminApi.allowBucketKey(new BucketKeyPermChangeRequest(bucketId, accessKeyId, toAllow));
        }

        return permissionsOf(
                garageAdminApi.denyBucketKey(new BucketKeyPermChangeRequest(bucketId, accessKeyId, toDeny)),
                accessKeyId
        );
    }

    public void revokeBucketKey(
            S3Connection s3Connection,
            String bucketId,
            String accessKeyId
    ) {
        var garageAdminApi = garageAdminClientFactory.create(s3Connection);

        // Checked through the listing rather than reading a failed call as "already gone".
        var bucketExists = garageAdminApi.listBuckets().stream()
                .anyMatch(bucket -> bucket.id().equals(bucketId));

        if (!bucketExists) {
            return;
        }

        var bucketInfo = garageAdminApi.getBucketInfo(bucketId);

        if (permissionsOf(bucketInfo, accessKeyId).isEmpty()) {
            return;
        }

        garageAdminApi.denyBucketKey(
                new BucketKeyPermChangeRequest(
                        bucketId,
                        accessKeyId,
                        new ApiBucketKeyPerm(true, true, true)
                )
        );
    }

    private BucketPermissions permissionsOf(
            GetBucketInfoResponse bucketInfo,
            String accessKeyId
    ) {
        return bucketInfo.keys().stream()
                .filter(key -> key.accessKeyId().equals(accessKeyId))
                .findFirst()
                .map(key -> new BucketPermissions(
                        key.permissions().read(),
                        key.permissions().write(),
                        key.permissions().owner()
                ))
                .orElse(BucketPermissions.NONE);
    }

    private AccessKeyInfo toAccessKeyInfo(GetKeyInfoResponse response) {
        return new AccessKeyInfo(
                response.accessKeyId(),
                response.name(),
                response.secretAccessKey(),
                new AccessKeyPermissions(response.permissions().createBucket())
        );
    }

    private BucketInfo toBucketInfo(
            GetBucketInfoResponse response,
            String name
    ) {
        return new BucketInfo(
                response.id(),
                name,
                new BucketQuotas(
                        response.quotas().maxSize(),
                        response.quotas().maxObjects()
                ),
                response.objects(),
                response.bytes()
        );
    }

    private String primaryName(GetBucketInfoResponse response) {
        return response.globalAliases().isEmpty()
                ? response.id()
                : response.globalAliases().getFirst();
    }
}
