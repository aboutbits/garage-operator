package it.aboutbits.garage.crd.bucketaccess;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.MaxReconciliationInterval;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.SecondaryToPrimaryMapper;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.quarkiverse.operatorsdk.annotations.AdditionalRBACRules;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import it.aboutbits.garage.core.BaseReconciler;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.Constants;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.core.adminapi.GarageService;
import it.aboutbits.garage.crd.accesskey.AccessKey;
import it.aboutbits.garage.crd.bucket.Bucket;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/// Grants react to `Bucket` and `AccessKey` watches rather than polling for them to become ready.
@Slf4j
@AdditionalRBACRules({
        @RBACRule(
                apiGroups = {""},
                resources = {"secrets"},
                verbs = {"get", "list", "watch"}
        ),
        @RBACRule(
                apiGroups = {Constants.CRD_GROUP},
                resources = {"buckets", "accesskeys"},
                verbs = {"get", "list", "watch"}
        ),
        @RBACRule(
                apiGroups = {""},
                resources = {"namespaces"},
                verbs = {"get"}
        )
})
@ControllerConfiguration(
        maxReconciliationInterval = @MaxReconciliationInterval(
                interval = BaseReconciler.RESYNC_INTERVAL_MINUTES,
                timeUnit = TimeUnit.MINUTES
        )
)
@RequiredArgsConstructor
@NullMarked
public class BucketAccessReconciler
        extends BaseReconciler<BucketAccess, BucketAccessStatus>
        implements Reconciler<BucketAccess>, Cleaner<BucketAccess> {
    private final BucketAccessService bucketAccessService;

    private final KubernetesClient kubernetesClient;
    private final GarageService garageService;

    @Override
    public List<EventSource<?, BucketAccess>> prepareEventSources(EventSourceContext<BucketAccess> context) {
        SecondaryToPrimaryMapper<Bucket> bucketToBucketAccessMapper = (Bucket bucket) -> context.getPrimaryCache()
                .list()
                .filter(bucketAccess -> bucketAccessService.refersTo(
                        bucketAccess.getSpec().getBucketRef(),
                        bucketAccess,
                        bucket
                ))
                .map(ResourceID::fromResource)
                .collect(Collectors.toSet());

        SecondaryToPrimaryMapper<AccessKey> accessKeyToBucketAccessMapper = (AccessKey accessKey) -> context.getPrimaryCache()
                .list()
                .filter(bucketAccess -> bucketAccessService.refersTo(
                        bucketAccess.getSpec().getAccessKeyRef(),
                        bucketAccess,
                        accessKey
                ))
                .map(ResourceID::fromResource)
                .collect(Collectors.toSet());

        var bucketEventSource = new InformerEventSource<>(
                InformerEventSourceConfiguration.from(Bucket.class, BucketAccess.class)
                        .withName("bucket")
                        .withSecondaryToPrimaryMapper(bucketToBucketAccessMapper)
                        .withNamespacesInheritedFromController()
                        .build(),
                context
        );

        var accessKeyEventSource = new InformerEventSource<>(
                InformerEventSourceConfiguration.from(AccessKey.class, BucketAccess.class)
                        .withName("accesskey")
                        .withSecondaryToPrimaryMapper(accessKeyToBucketAccessMapper)
                        .withNamespacesInheritedFromController()
                        .build(),
                context
        );

        return List.of(bucketEventSource, accessKeyEventSource);
    }

    @Override
    public UpdateControl<BucketAccess> reconcile(
            BucketAccess resource,
            Context<BucketAccess> context
    ) {
        var spec = resource.getSpec();
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Reconciling BucketAccess [resource={}/{}, status.phase={}]",
                namespace,
                name,
                status.getPhase()
        );

        var bucketRef = bucketAccessService.qualify(spec.getBucketRef(), namespace);

        var bucket = kubernetesClient.resources(Bucket.class)
                .inNamespace(bucketRef.getNamespace())
                .withName(bucketRef.getName())
                .get();

        // A Bucket or AccessKey being deleted still reports READY, so treat it as not ready.
        //noinspection ConstantConditions
        if (bucket == null || bucket.getMetadata().getDeletionTimestamp() != null
                || bucket.getStatus() == null
                || bucket.getStatus().getPhase() != CRPhase.READY
                || bucket.getStatus().getBucketId() == null
        ) {
            return pending(
                    resource,
                    status,
                    "The specified Bucket does not exist or is not ready yet [spec.bucketRef=%s/%s]".formatted(
                            bucketRef.getNamespace(),
                            bucketRef.getName()
                    )
            );
        }

        var accessKeyRef = bucketAccessService.qualify(spec.getAccessKeyRef(), namespace);

        var accessKey = kubernetesClient.resources(AccessKey.class)
                .inNamespace(accessKeyRef.getNamespace())
                .withName(accessKeyRef.getName())
                .get();

        //noinspection ConstantConditions
        if (accessKey == null || accessKey.getMetadata().getDeletionTimestamp() != null
                || accessKey.getStatus() == null
                || accessKey.getStatus().getPhase() != CRPhase.READY
                || accessKey.getStatus().getAccessKeyId() == null
        ) {
            return pending(
                    resource,
                    status,
                    "The specified AccessKey does not exist or is not ready yet [spec.accessKeyRef=%s/%s]".formatted(
                            accessKeyRef.getNamespace(),
                            accessKeyRef.getName()
                    )
            );
        }

        // Different S3Connections is an ERROR no retry fixes; only a spec change (seen by the watches) does.
        var bucketConnectionRef = bucketAccessService.qualify(
                bucket.getSpec().getConnectionRef(),
                bucket.getMetadata().getNamespace()
        );
        var accessKeyConnectionRef = bucketAccessService.qualify(
                accessKey.getSpec().getConnectionRef(),
                accessKey.getMetadata().getNamespace()
        );

        if (!bucketAccessService.sameResource(bucketConnectionRef, accessKeyConnectionRef)) {
            try {
                // A grant recorded for an earlier resolution of the references stays behind untracked otherwise.
                if (!revokeRecordedGrant(resource, status)) {
                    return previousGrantNotRevocable(resource, status);
                }
            } catch (Exception e) {
                return handleError(
                        resource,
                        status,
                        e
                );
            }

            status.updatePhase(CRPhase.ERROR)
                    .setMessage("The Bucket and the AccessKey live on different S3Connections. Recreate one of them on the same S3Connection [bucket.connectionRef=%s/%s, accessKey.connectionRef=%s/%s]".formatted(
                            bucketConnectionRef.getNamespace(),
                            bucketConnectionRef.getName(),
                            accessKeyConnectionRef.getNamespace(),
                            accessKeyConnectionRef.getName()
                    ));

            return UpdateControl.patchStatus(resource);
        }

        var s3ConnectionOptional = getReferencedS3Connection(
                kubernetesClient,
                resource,
                bucketConnectionRef
        );

        if (s3ConnectionOptional.isEmpty()) {
            return pending(
                    resource,
                    status,
                    "The specified S3Connection does not exist or is not ready yet [bucket.connectionRef=%s/%s]".formatted(
                            bucketConnectionRef.getNamespace(),
                            bucketConnectionRef.getName()
                    )
            );
        }

        return grant(
                resource,
                status,
                s3ConnectionOptional.get(),
                bucketConnectionRef,
                bucket.getStatus().getBucketId(),
                accessKey.getStatus().getAccessKeyId()
        );
    }

    @Override
    public DeleteControl cleanup(
            BucketAccess resource,
            Context<BucketAccess> context
    ) {
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Revoking BucketAccess [resource={}/{}, status.phase={}]",
                namespace,
                name,
                status.getPhase()
        );

        if (status.getPhase() != CRPhase.DELETING) {
            status.updatePhase(CRPhase.DELETING)
                    .setMessage("Revoking permissions");

            context.getClient().resource(resource).patchStatus();

            return DeleteControl.noFinalizerRemoval()
                    .rescheduleAfter(100, TimeUnit.MILLISECONDS);
        }

        var bucketId = status.getBucketId();
        var accessKeyId = status.getAccessKeyId();
        var connectionName = status.getConnectionName();

        if (bucketId == null || accessKeyId == null || connectionName == null) {
            return DeleteControl.defaultDelete();
        }

        var connectionRef = new ResourceRef();

        connectionRef.setNamespace(status.getConnectionNamespace());
        connectionRef.setName(connectionName);

        var s3ConnectionOptional = getReferencedS3Connection(
                kubernetesClient,
                resource,
                connectionRef
        );

        if (s3ConnectionOptional.isEmpty()) {
            if (isNamespaceTerminating(kubernetesClient, resource)) {
                // Release without backend cleanup, otherwise the namespace deletion hangs forever.
                log.warn(
                        "The namespace is being deleted and the S3Connection is gone, releasing the BucketAccess without revoking the grant on the backend [resource={}/{}, bucketId={}, accessKeyId={}]",
                        namespace,
                        name,
                        bucketId,
                        accessKeyId
                );

                return DeleteControl.defaultDelete();
            }

            return failCleanup(
                    resource,
                    status,
                    context,
                    "The S3Connection the permissions were granted on no longer exists or is not ready yet [connection=%s/%s]".formatted(
                            getResourceNamespaceOrOwn(resource, connectionRef.getNamespace()),
                            connectionRef.getName()
                    )
            );
        }

        var s3Connection = s3ConnectionOptional.get();

        try {
            garageService.revokeBucketKey(s3Connection, bucketId, accessKeyId);

            return DeleteControl.defaultDelete();
        } catch (Exception e) {
            log.error(
                    "Failed to revoke BucketAccess [resource=%s/%s]".formatted(namespace, name),
                    e
            );

            return failCleanup(
                    resource,
                    status,
                    context,
                    "Revocation failed: %s".formatted(e.getMessage())
            );
        }
    }

    @Override
    protected BucketAccessStatus newStatus() {
        return new BucketAccessStatus();
    }

    private UpdateControl<BucketAccess> grant(
            BucketAccess resource,
            BucketAccessStatus status,
            S3Connection s3Connection,
            ResourceRef connectionRef,
            String bucketId,
            String accessKeyId
    ) {
        try {
            // Before anything is recorded: deleting a refused BucketAccess must not revoke the other one's grant.
            requireNotGrantedElsewhere(resource, bucketId, accessKeyId);

            // A Bucket or AccessKey recreated under the same name resolves to a different backend
            // object; revoke the previous grant first, or it stays behind untracked.
            if (!revokePreviousGrant(resource, status, connectionRef, bucketId, accessKeyId)) {
                return previousGrantNotRevocable(resource, status);
            }

            // Recorded before granting, so a half-done grant is still revoked on delete.
            status.setBucketId(bucketId)
                    .setAccessKeyId(accessKeyId)
                    .setConnectionNamespace(connectionRef.getNamespace())
                    .setConnectionName(connectionRef.getName());

            var desiredPermissions = bucketAccessService.desiredPermissions(resource.getSpec().getPermissions());
            var currentPermissions = garageService.getBucketKeyPermissions(s3Connection, bucketId, accessKeyId);

            String message = null;

            if (!currentPermissions.equals(desiredPermissions)) {
                log.info(
                        "Updating BucketAccess permissions [resource={}/{}, bucketId={}, accessKeyId={}]",
                        resource.getMetadata().getNamespace(),
                        resource.getMetadata().getName(),
                        bucketId,
                        accessKeyId
                );

                garageService.setBucketKeyPermissions(
                        s3Connection,
                        bucketId,
                        accessKeyId,
                        desiredPermissions
                );

                message = "Permissions granted";
            }

            status.updatePhase(CRPhase.READY)
                    .setMessage(message);

            return UpdateControl.patchStatus(resource);
        } catch (Exception e) {
            return handleError(
                    resource,
                    status,
                    e
            );
        }
    }

    /// @return `false` if the previous grant must be revoked but its connection is not usable yet
    private boolean revokePreviousGrant(
            BucketAccess resource,
            BucketAccessStatus status,
            ResourceRef connectionRef,
            String bucketId,
            String accessKeyId
    ) {
        var previousConnectionRef = recordedConnectionRef(status);

        var unchanged = previousConnectionRef != null
                && bucketId.equals(status.getBucketId())
                && accessKeyId.equals(status.getAccessKeyId())
                && bucketAccessService.sameResource(
                        bucketAccessService.qualify(previousConnectionRef, resource.getMetadata().getNamespace()),
                        connectionRef
                );

        if (unchanged) {
            return true;
        }

        return revokeRecordedGrant(resource, status);
    }

    /// Revokes the grant recorded in the status, if any, and forgets it.
    ///
    /// @return `false` if a grant is recorded but its connection is not usable yet
    private boolean revokeRecordedGrant(
            BucketAccess resource,
            BucketAccessStatus status
    ) {
        var previousBucketId = status.getBucketId();
        var previousAccessKeyId = status.getAccessKeyId();
        var previousConnectionRef = recordedConnectionRef(status);

        if (previousBucketId == null || previousAccessKeyId == null || previousConnectionRef == null) {
            return true;
        }

        var previousConnectionOptional = getReferencedS3Connection(
                kubernetesClient,
                resource,
                previousConnectionRef
        );

        if (previousConnectionOptional.isEmpty()) {
            return false;
        }

        log.info(
                "Revoking the previous grant, the references now resolve to different backend objects [resource={}/{}, status.bucketId={}, status.accessKeyId={}]",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName(),
                previousBucketId,
                previousAccessKeyId
        );

        garageService.revokeBucketKey(previousConnectionOptional.get(), previousBucketId, previousAccessKeyId);

        status.setBucketId(null)
                .setAccessKeyId(null)
                .setConnectionNamespace(null)
                .setConnectionName(null);

        return true;
    }

    private @Nullable ResourceRef recordedConnectionRef(BucketAccessStatus status) {
        var connectionName = status.getConnectionName();

        if (connectionName == null) {
            return null;
        }

        var connectionRef = new ResourceRef();

        connectionRef.setNamespace(status.getConnectionNamespace());
        connectionRef.setName(connectionName);

        return connectionRef;
    }

    /// Two grants on the same pair would overwrite each other's permissions at every reconcile.
    private void requireNotGrantedElsewhere(
            BucketAccess resource,
            String bucketId,
            String accessKeyId
    ) {
        var grantedElsewhere = kubernetesClient.resources(BucketAccess.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .filter(other -> !Objects.equals(other.getMetadata().getUid(), resource.getMetadata().getUid()))
                .filter(other -> other.getStatus() != null
                        && bucketId.equals(other.getStatus().getBucketId())
                        && accessKeyId.equals(other.getStatus().getAccessKeyId()))
                .findFirst();

        if (grantedElsewhere.isPresent()) {
            var other = grantedElsewhere.get();

            throw new IllegalStateException(
                    "The AccessKey already has a grant on this bucket from BucketAccess %s/%s. Delete one of the two BucketAccesses [bucketId=%s, accessKeyId=%s]".formatted(
                            other.getMetadata().getNamespace(),
                            other.getMetadata().getName(),
                            bucketId,
                            accessKeyId
                    )
            );
        }
    }

    private UpdateControl<BucketAccess> previousGrantNotRevocable(
            BucketAccess resource,
            BucketAccessStatus status
    ) {
        return pending(
                resource,
                status,
                "The previous grant cannot be revoked yet, because its S3Connection does not exist or is not ready [connection=%s/%s]".formatted(
                        status.getConnectionNamespace(),
                        status.getConnectionName()
                )
        );
    }

    private UpdateControl<BucketAccess> pending(
            BucketAccess resource,
            BucketAccessStatus status,
            String message
    ) {
        status.updatePhase(CRPhase.PENDING)
                .setMessage(message);

        // Only a safety net; the `Bucket` and `AccessKey` watches normally trigger far sooner.
        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
    }
}
