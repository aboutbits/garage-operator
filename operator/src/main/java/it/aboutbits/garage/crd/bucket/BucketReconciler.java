package it.aboutbits.garage.crd.bucket;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.MaxReconciliationInterval;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkiverse.operatorsdk.annotations.AdditionalRBACRules;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import it.aboutbits.garage.core.BaseReconciler;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.ReclaimPolicy;
import it.aboutbits.garage.core.adminapi.BucketInfo;
import it.aboutbits.garage.core.adminapi.BucketNotEmptyException;
import it.aboutbits.garage.core.adminapi.GarageService;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@AdditionalRBACRules({
        @RBACRule(
                apiGroups = {""},
                resources = {"secrets"},
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
public class BucketReconciler
        extends BaseReconciler<Bucket, BucketStatus>
        implements Reconciler<Bucket>, Cleaner<Bucket> {
    private static final long RETRY_SECONDS = 60;

    private final BucketService bucketService;

    private final KubernetesClient kubernetesClient;
    private final GarageService garageService;

    @Override
    public UpdateControl<Bucket> reconcile(
            Bucket resource,
            Context<Bucket> context
    ) {
        var spec = resource.getSpec();
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Reconciling Bucket [resource={}/{}, spec.name={}, status.phase={}]",
                namespace,
                name,
                spec.getName(),
                status.getPhase()
        );

        var connectionRef = spec.getConnectionRef();

        var s3ConnectionOptional = getReferencedS3Connection(
                kubernetesClient,
                resource,
                connectionRef
        );

        if (s3ConnectionOptional.isEmpty()) {
            status.setPhase(CRPhase.PENDING)
                    .setMessage("The specified S3Connection does not exist or is not ready yet [resource=%s/%s]".formatted(
                            getResourceNamespaceOrOwn(resource, connectionRef.getNamespace()),
                            connectionRef.getName()
                    ));

            return UpdateControl.patchStatus(resource)
                    .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
        }

        var s3Connection = s3ConnectionOptional.get();

        try {
            return reconcile(
                    s3Connection,
                    resource,
                    status
            );
        } catch (Exception e) {
            return handleError(
                    resource,
                    status,
                    e
            );
        }
    }

    @Override
    public DeleteControl cleanup(
            Bucket resource,
            Context<Bucket> context
    ) {
        var spec = resource.getSpec();
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "{}ing Bucket [resource={}/{}, spec.name={}, status.phase={}]",
                spec.getReclaimPolicy().toValue(),
                namespace,
                name,
                spec.getName(),
                status.getPhase()
        );

        if (status.getPhase() != CRPhase.DELETING) {
            status.setPhase(CRPhase.DELETING);

            if (spec.getReclaimPolicy() == ReclaimPolicy.DELETE) {
                status.setMessage("Bucket deletion in progress");
            }

            context.getClient().resource(resource).patchStatus();

            return DeleteControl.noFinalizerRemoval()
                    .rescheduleAfter(100, TimeUnit.MILLISECONDS);
        }

        // Retain only removes the CR, the bucket stays on the backend
        if (spec.getReclaimPolicy() == ReclaimPolicy.RETAIN) {
            return DeleteControl.defaultDelete();
        }

        // Never reached the backend: delete directly, even if the S3Connection is missing.
        var bucketId = status.getBucketId();

        if (bucketId == null) {
            log.info(
                    "The Bucket never reached the backend, deleting the resource directly [resource={}/{}]",
                    namespace,
                    name
            );

            return DeleteControl.defaultDelete();
        }

        var connectionRef = spec.getConnectionRef();

        var s3ConnectionOptional = getReferencedS3Connection(
                kubernetesClient,
                resource,
                connectionRef
        );

        if (s3ConnectionOptional.isEmpty()) {
            if (isNamespaceTerminating(kubernetesClient, resource)) {
                // Release without backend cleanup (no data is lost), otherwise the namespace deletion hangs.
                log.warn(
                        "The namespace is being deleted and the S3Connection is gone, releasing the Bucket without deleting the bucket on the backend [resource={}/{}, bucket.id={}]",
                        namespace,
                        name,
                        bucketId
                );

                return DeleteControl.defaultDelete();
            }

            return failCleanup(
                    resource,
                    status,
                    context,
                    "The specified S3Connection no longer exists or is not ready yet [resource=%s/%s]".formatted(
                            getResourceNamespaceOrOwn(resource, connectionRef.getNamespace()),
                            connectionRef.getName()
                    )
            );
        }

        var s3Connection = s3ConnectionOptional.get();

        try {
            // By recorded id, never by name: the name may now belong to another bucket.
            var bucketOptional = garageService.getBucket(s3Connection, bucketId);

            if (bucketOptional.isEmpty()) {
                log.info(
                        "The Bucket no longer exists on the backend, nothing to delete [resource={}/{}, bucket.id={}]",
                        namespace,
                        name,
                        bucketId
                );

                return DeleteControl.defaultDelete();
            }

            garageService.deleteBucket(
                    s3Connection,
                    bucketId
            );

            return DeleteControl.defaultDelete();
        } catch (BucketNotEmptyException e) {
            // Keep the finalizer with a visible message on purpose: emptying the bucket must be a decision.
            log.error(
                    "Refusing to delete a Bucket that still holds objects [resource={}/{}, spec.name={}]",
                    namespace,
                    name,
                    spec.getName()
            );

            return failCleanup(
                    resource,
                    status,
                    context,
                    "Deletion failed: the bucket still holds objects. Empty it, or set reclaimPolicy to Retain to release the resource without deleting it."
            );
        } catch (Exception e) {
            log.error(
                    "Failed to delete Bucket [resource=%s/%s, spec.name=%s]".formatted(
                            namespace,
                            name,
                            spec.getName()
                    ),
                    e
            );

            return failCleanup(
                    resource,
                    status,
                    context,
                    "Deletion failed: %s".formatted(e.getMessage())
            );
        }
    }

    @Override
    protected BucketStatus newStatus() {
        return new BucketStatus();
    }

    private UpdateControl<Bucket> reconcile(
            S3Connection s3Connection,
            Bucket resource,
            BucketStatus status
    ) {
        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        var spec = resource.getSpec();

        var desiredQuotas = bucketService.desiredQuotas(spec.getQuotas());

        var bucketOptional = findManagedBucket(
                s3Connection,
                resource,
                status
        );

        BucketInfo bucket;
        String message;

        if (bucketOptional.isEmpty()) {
            log.info(
                    "Creating Bucket [resource={}/{}, spec.name={}]",
                    namespace,
                    name,
                    spec.getName()
            );

            bucket = garageService.createBucket(s3Connection, spec.getName());

            // Recorded before the quotas can fail: status is patched on errors too, so cleanup finds the bucket.
            status.setBucketId(bucket.id());

            if (!desiredQuotas.isEmpty()) {
                bucket = garageService.updateBucketQuotas(
                        s3Connection,
                        bucket.id(),
                        desiredQuotas
                );
            }

            message = "Bucket created";
        } else {
            bucket = bucketOptional.get();

            status.setBucketId(bucket.id());

            if (bucketService.quotasMatch(bucket.quotas(), desiredQuotas)) {
                log.info(
                        "Bucket up-to-date [resource={}/{}, spec.name={}]",
                        namespace,
                        name,
                        spec.getName()
                );

                message = null;
            } else {
                log.info(
                        "Updating Bucket quotas [resource={}/{}, spec.name={}]",
                        namespace,
                        name,
                        spec.getName()
                );

                bucket = garageService.updateBucketQuotas(
                        s3Connection,
                        bucket.id(),
                        desiredQuotas
                );

                message = "Bucket quotas updated";
            }
        }

        status.setBucketId(bucket.id())
                .setObjects(bucket.objects())
                .setBytes(bucket.bytes());

        status.setPhase(CRPhase.READY)
                .setMessage(message);

        return UpdateControl.patchStatus(resource);
    }

    /// After the first bind the bucket is addressed by its recorded id; the name is only used to
    /// discover (or adopt) it the first time, or again if the recorded bucket was deleted.
    private Optional<BucketInfo> findManagedBucket(
            S3Connection s3Connection,
            Bucket resource,
            BucketStatus status
    ) {
        var recordedBucketId = status.getBucketId();

        if (recordedBucketId != null) {
            var recorded = garageService.getBucket(s3Connection, recordedBucketId);

            if (recorded.isPresent()) {
                return recorded;
            }

            log.warn(
                    "The recorded bucket no longer exists on the backend, looking it up by name [resource={}/{}, bucket.id={}]",
                    resource.getMetadata().getNamespace(),
                    resource.getMetadata().getName(),
                    recordedBucketId
            );
        }

        var found = garageService.findBucket(s3Connection, resource.getSpec().getName());

        found.ifPresent(bucket -> requireNotManagedElsewhere(resource, bucket.id()));

        return found;
    }

    /// Refuse to adopt a bucket another `Bucket` manages: they would fight over quotas and deletion.
    private void requireNotManagedElsewhere(
            Bucket resource,
            String bucketId
    ) {
        var managedElsewhere = kubernetesClient.resources(Bucket.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .filter(other -> !Objects.equals(other.getMetadata().getUid(), resource.getMetadata().getUid()))
                .filter(other -> other.getStatus() != null && bucketId.equals(other.getStatus().getBucketId()))
                .findFirst();

        if (managedElsewhere.isPresent()) {
            var other = managedElsewhere.get();

            throw new IllegalStateException(
                    "The bucket named %s is already managed by Bucket %s/%s [bucket.id=%s]".formatted(
                            resource.getSpec().getName(),
                            other.getMetadata().getNamespace(),
                            other.getMetadata().getName(),
                            bucketId
                    )
            );
        }
    }

    private DeleteControl failCleanup(
            Bucket resource,
            BucketStatus status,
            Context<Bucket> context,
            String message
    ) {
        status.setMessage(message);

        context.getClient().resource(resource).patchStatus();

        return DeleteControl.noFinalizerRemoval()
                .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
    }
}
