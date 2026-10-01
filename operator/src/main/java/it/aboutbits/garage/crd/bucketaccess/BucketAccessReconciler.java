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
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.core.adminapi.GarageService;
import it.aboutbits.garage.crd.accesskey.AccessKey;
import it.aboutbits.garage.crd.bucket.Bucket;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.util.List;
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
                apiGroups = {"garage.aboutbits.it"},
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
    /// Only a safety net; the `Bucket` and `AccessKey` watches normally trigger far sooner.
    private static final long RETRY_SECONDS = 60;

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
                    "The specified Bucket does not exist or is not ready yet [resource=%s/%s]".formatted(
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
                    "The specified AccessKey does not exist or is not ready yet [resource=%s/%s]".formatted(
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
            status.setPhase(CRPhase.ERROR)
                    .setMessage("The Bucket and the AccessKey live on different S3Connections [bucket.connection=%s/%s, accessKey.connection=%s/%s]".formatted(
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
                    "The specified S3Connection does not exist or is not ready yet [resource=%s/%s]".formatted(
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
            status.setPhase(CRPhase.DELETING)
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

        connectionRef.setName(connectionName);
        connectionRef.setNamespace(status.getConnectionNamespace());

        var s3ConnectionOptional = getReferencedS3Connection(
                kubernetesClient,
                resource,
                connectionRef
        );

        if (s3ConnectionOptional.isEmpty()) {
            if (isNamespaceTerminating(kubernetesClient, resource)) {
                // Release without backend cleanup, otherwise the namespace deletion hangs forever.
                log.warn(
                        "The namespace is being deleted and the S3Connection is gone, releasing the BucketAccess without revoking the grant on the backend [resource={}/{}, bucket.id={}, accessKey.id={}]",
                        namespace,
                        name,
                        bucketId,
                        accessKeyId
                );

                return DeleteControl.defaultDelete();
            }

            status.setMessage("The S3Connection the permissions were granted on no longer exists or is not ready yet [resource=%s/%s]".formatted(
                    getResourceNamespaceOrOwn(resource, connectionRef.getNamespace()),
                    connectionRef.getName()
            ));

            context.getClient().resource(resource).patchStatus();

            return DeleteControl.noFinalizerRemoval()
                    .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
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

            status.setMessage("Revocation failed: %s".formatted(e.getMessage()));

            context.getClient().resource(resource).patchStatus();

            return DeleteControl.noFinalizerRemoval()
                    .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
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
            // A Bucket or AccessKey recreated under the same name resolves to a different backend
            // object; revoke the previous grant first, or it stays behind untracked.
            if (!revokePreviousGrant(resource, status, connectionRef, bucketId, accessKeyId)) {
                return pending(
                        resource,
                        status,
                        "The previous grant cannot be revoked yet, because its S3Connection does not exist or is not ready [resource=%s/%s]".formatted(
                                status.getConnectionNamespace(),
                                status.getConnectionName()
                        )
                );
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
                        "Updating BucketAccess permissions [resource={}/{}, bucket.id={}, accessKey.id={}]",
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

            status.setPhase(CRPhase.READY)
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
        var previousBucketId = status.getBucketId();
        var previousAccessKeyId = status.getAccessKeyId();
        var previousConnectionName = status.getConnectionName();

        if (previousBucketId == null || previousAccessKeyId == null || previousConnectionName == null) {
            return true;
        }

        var previousConnectionRef = new ResourceRef();

        previousConnectionRef.setName(previousConnectionName);
        previousConnectionRef.setNamespace(status.getConnectionNamespace());

        var unchanged = previousBucketId.equals(bucketId)
                && previousAccessKeyId.equals(accessKeyId)
                && bucketAccessService.sameResource(
                        bucketAccessService.qualify(previousConnectionRef, resource.getMetadata().getNamespace()),
                        connectionRef
                );

        if (unchanged) {
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

        var previousConnection = previousConnectionOptional.get();

        log.info(
                "Revoking the previous grant, the references now resolve to different backend objects [resource={}/{}, previous.bucket.id={}, previous.accessKey.id={}]",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName(),
                previousBucketId,
                previousAccessKeyId
        );

        garageService.revokeBucketKey(previousConnection, previousBucketId, previousAccessKeyId);

        return true;
    }

    private UpdateControl<BucketAccess> pending(
            BucketAccess resource,
            BucketAccessStatus status,
            String message
    ) {
        status.setPhase(CRPhase.PENDING)
                .setMessage(message);

        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
    }
}
