package it.aboutbits.garage.crd.garagebucketaccess;

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
import it.aboutbits.garage.crd.garageaccesskey.GarageAccessKey;
import it.aboutbits.garage.crd.garagebucket.GarageBucket;
import it.aboutbits.garage.crd.garageclusterconnection.GarageClusterConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/// Grants react to `GarageBucket` and `GarageAccessKey` watches rather than polling for them to become ready.
@Slf4j
@AdditionalRBACRules({
        @RBACRule(
                apiGroups = {""},
                resources = {"secrets"},
                verbs = {"get", "list", "watch"}
        ),
        @RBACRule(
                apiGroups = {Constants.CRD_GROUP},
                resources = {"garagebuckets", "garageaccesskeys"},
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
public class GarageBucketAccessReconciler
        extends BaseReconciler<GarageBucketAccess, GarageBucketAccessStatus>
        implements Reconciler<GarageBucketAccess>, Cleaner<GarageBucketAccess> {
    /// The one that already holds the grant first, so a newer one created in the same second cannot take it over.
    /// Then the oldest: RFC 3339 timestamps in UTC sort as strings, and the uid breaks a tie within the same second.
    private static final Comparator<GarageBucketAccess> GRANT_ORDER = Comparator
            .comparing((GarageBucketAccess bucketAccess) -> !hasRecordedGrant(bucketAccess))
            .thenComparing(bucketAccess -> bucketAccess.getMetadata().getCreationTimestamp())
            .thenComparing(bucketAccess -> bucketAccess.getMetadata().getUid());

    private final GarageBucketAccessService bucketAccessService;

    private final KubernetesClient kubernetesClient;
    private final GarageService garageService;

    @Override
    public List<EventSource<?, GarageBucketAccess>> prepareEventSources(EventSourceContext<GarageBucketAccess> context) {
        SecondaryToPrimaryMapper<GarageBucket> bucketToBucketAccessMapper = (GarageBucket bucket) -> context.getPrimaryCache()
                .list()
                .filter(bucketAccess -> bucketAccessService.refersTo(
                        bucketAccess.getSpec().getBucketRef(),
                        bucketAccess,
                        bucket
                ))
                .map(ResourceID::fromResource)
                .collect(Collectors.toSet());

        SecondaryToPrimaryMapper<GarageAccessKey> accessKeyToBucketAccessMapper = (GarageAccessKey accessKey) -> context.getPrimaryCache()
                .list()
                .filter(bucketAccess -> bucketAccessService.refersTo(
                        bucketAccess.getSpec().getAccessKeyRef(),
                        bucketAccess,
                        accessKey
                ))
                .map(ResourceID::fromResource)
                .collect(Collectors.toSet());

        var bucketEventSource = new InformerEventSource<>(
                InformerEventSourceConfiguration.from(GarageBucket.class, GarageBucketAccess.class)
                        .withName("bucket")
                        .withSecondaryToPrimaryMapper(bucketToBucketAccessMapper)
                        .withNamespacesInheritedFromController()
                        .build(),
                context
        );

        var accessKeyEventSource = new InformerEventSource<>(
                InformerEventSourceConfiguration.from(GarageAccessKey.class, GarageBucketAccess.class)
                        .withName("accesskey")
                        .withSecondaryToPrimaryMapper(accessKeyToBucketAccessMapper)
                        .withNamespacesInheritedFromController()
                        .build(),
                context
        );

        return List.of(bucketEventSource, accessKeyEventSource);
    }

    @Override
    public UpdateControl<GarageBucketAccess> reconcile(
            GarageBucketAccess resource,
            Context<GarageBucketAccess> context
    ) {
        var spec = resource.getSpec();
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Reconciling GarageBucketAccess [resource={}/{}, status.phase={}]",
                namespace,
                name,
                status.getPhase()
        );

        var bucketRef = bucketAccessService.qualify(spec.getBucketRef(), namespace);

        var bucket = kubernetesClient.resources(GarageBucket.class)
                .inNamespace(bucketRef.getNamespace())
                .withName(bucketRef.getName())
                .get();

        // A GarageBucket or GarageAccessKey being deleted still reports READY, so treat it as not ready.
        //noinspection ConstantConditions
        if (bucket == null || bucket.getMetadata().getDeletionTimestamp() != null
                || bucket.getStatus() == null
                || bucket.getStatus().getPhase() != CRPhase.READY
                || bucket.getStatus().getBucketId() == null
        ) {
            return pending(
                    resource,
                    status,
                    "The specified GarageBucket does not exist or is not ready yet [spec.bucketRef=%s/%s]".formatted(
                            bucketRef.getNamespace(),
                            bucketRef.getName()
                    )
            );
        }

        var accessKeyRef = bucketAccessService.qualify(spec.getAccessKeyRef(), namespace);

        var accessKey = kubernetesClient.resources(GarageAccessKey.class)
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
                    "The specified GarageAccessKey does not exist or is not ready yet [spec.accessKeyRef=%s/%s]".formatted(
                            accessKeyRef.getNamespace(),
                            accessKeyRef.getName()
                    )
            );
        }

        // Different GarageClusterConnections is an ERROR no retry fixes; only a spec change (seen by the watches) does.
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
                    .setMessage("The GarageBucket and the GarageAccessKey live on different GarageClusterConnections. Recreate one of them on the same GarageClusterConnection [bucket.connectionRef=%s/%s, accessKey.connectionRef=%s/%s]".formatted(
                            bucketConnectionRef.getNamespace(),
                            bucketConnectionRef.getName(),
                            accessKeyConnectionRef.getNamespace(),
                            accessKeyConnectionRef.getName()
                    ));

            return UpdateControl.patchStatus(resource);
        }

        var garageClusterConnectionOptional = getReferencedGarageClusterConnection(
                kubernetesClient,
                resource,
                bucketConnectionRef
        );

        if (garageClusterConnectionOptional.isEmpty()) {
            return pending(
                    resource,
                    status,
                    "The specified GarageClusterConnection does not exist or is not ready yet [bucket.connectionRef=%s/%s]".formatted(
                            bucketConnectionRef.getNamespace(),
                            bucketConnectionRef.getName()
                    )
            );
        }

        try {
            // Before anything is recorded: deleting a refused GarageBucketAccess must not revoke the other one's grant.
            requireNotGrantedElsewhere(resource, bucketRef, accessKeyRef);
        } catch (Exception e) {
            return handleError(
                    resource,
                    status,
                    e
            );
        }

        return grant(
                resource,
                status,
                garageClusterConnectionOptional.get(),
                bucketConnectionRef,
                bucket.getStatus().getBucketId(),
                accessKey.getStatus().getAccessKeyId()
        );
    }

    @Override
    public DeleteControl cleanup(
            GarageBucketAccess resource,
            Context<GarageBucketAccess> context
    ) {
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Revoking GarageBucketAccess [resource={}/{}, status.phase={}]",
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

        var garageClusterConnectionOptional = getReferencedGarageClusterConnection(
                kubernetesClient,
                resource,
                connectionRef
        );

        if (garageClusterConnectionOptional.isEmpty()) {
            if (isNamespaceTerminating(kubernetesClient, resource)) {
                // Release without backend cleanup, otherwise the namespace deletion hangs forever.
                log.warn(
                        "The namespace is being deleted and the GarageClusterConnection is gone, releasing the GarageBucketAccess without revoking the grant on the backend [resource={}/{}, bucketId={}, accessKeyId={}]",
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
                    "The GarageClusterConnection the permissions were granted on no longer exists or is not ready yet [connection=%s/%s]".formatted(
                            getResourceNamespaceOrOwn(resource, connectionRef.getNamespace()),
                            connectionRef.getName()
                    )
            );
        }

        var garageClusterConnection = garageClusterConnectionOptional.get();

        try {
            garageService.revokeBucketKey(garageClusterConnection, bucketId, accessKeyId);

            return DeleteControl.defaultDelete();
        } catch (Exception e) {
            log.error(
                    "Failed to revoke GarageBucketAccess [resource=%s/%s]".formatted(namespace, name),
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
    protected GarageBucketAccessStatus newStatus() {
        return new GarageBucketAccessStatus();
    }

    private UpdateControl<GarageBucketAccess> grant(
            GarageBucketAccess resource,
            GarageBucketAccessStatus status,
            GarageClusterConnection garageClusterConnection,
            ResourceRef connectionRef,
            String bucketId,
            String accessKeyId
    ) {
        try {
            // A GarageBucket or GarageAccessKey recreated under the same name resolves to a different backend
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
            var currentPermissions = garageService.getBucketKeyPermissions(garageClusterConnection, bucketId, accessKeyId);

            String message = null;

            if (!currentPermissions.equals(desiredPermissions)) {
                log.info(
                        "Updating GarageBucketAccess permissions [resource={}/{}, bucketId={}, accessKeyId={}]",
                        resource.getMetadata().getNamespace(),
                        resource.getMetadata().getName(),
                        bucketId,
                        accessKeyId
                );

                garageService.setBucketKeyPermissions(
                        garageClusterConnection,
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
            GarageBucketAccess resource,
            GarageBucketAccessStatus status,
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
            GarageBucketAccess resource,
            GarageBucketAccessStatus status
    ) {
        var previousBucketId = status.getBucketId();
        var previousAccessKeyId = status.getAccessKeyId();
        var previousConnectionRef = recordedConnectionRef(status);

        if (previousBucketId == null || previousAccessKeyId == null || previousConnectionRef == null) {
            return true;
        }

        var previousConnectionOptional = getReferencedGarageClusterConnection(
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

    private @Nullable ResourceRef recordedConnectionRef(GarageBucketAccessStatus status) {
        var connectionName = status.getConnectionName();

        if (connectionName == null) {
            return null;
        }

        var connectionRef = new ResourceRef();

        connectionRef.setNamespace(status.getConnectionNamespace());
        connectionRef.setName(connectionName);

        return connectionRef;
    }

    /// Two grants on the same pair would overwrite each other's permissions at every reconcile, so only one grants, in
    /// [#GRANT_ORDER]. The pair is found from the spec, not from the recorded ids: two GarageBucketAccesses applied together
    /// reconcile before either records its ids. A `GarageBucket` and a `GarageAccessKey` each have one manager, so the qualified
    /// references identify the pair. One that is being deleted still counts, or its revoke would race the new grant.
    private void requireNotGrantedElsewhere(
            GarageBucketAccess resource,
            ResourceRef bucketRef,
            ResourceRef accessKeyRef
    ) {
        var olderGrant = kubernetesClient.resources(GarageBucketAccess.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .filter(other -> !Objects.equals(other.getMetadata().getUid(), resource.getMetadata().getUid()))
                .filter(other -> grantsPair(other, bucketRef, accessKeyRef))
                .filter(other -> GRANT_ORDER.compare(other, resource) < 0)
                .min(GRANT_ORDER);

        if (olderGrant.isPresent()) {
            var other = olderGrant.get();

            throw new IllegalStateException(
                    "The GarageAccessKey already has a grant on this bucket from GarageBucketAccess %s/%s. Delete one of the two GarageBucketAccesses [spec.bucketRef=%s/%s, spec.accessKeyRef=%s/%s]".formatted(
                            other.getMetadata().getNamespace(),
                            other.getMetadata().getName(),
                            bucketRef.getNamespace(),
                            bucketRef.getName(),
                            accessKeyRef.getNamespace(),
                            accessKeyRef.getName()
                    )
            );
        }
    }

    private static boolean hasRecordedGrant(GarageBucketAccess bucketAccess) {
        var status = bucketAccess.getStatus();

        //noinspection ConstantConditions
        return status != null && status.getBucketId() != null && status.getAccessKeyId() != null;
    }

    private boolean grantsPair(
            GarageBucketAccess other,
            ResourceRef bucketRef,
            ResourceRef accessKeyRef
    ) {
        var otherNamespace = other.getMetadata().getNamespace();

        return bucketAccessService.sameResource(
                bucketAccessService.qualify(other.getSpec().getBucketRef(), otherNamespace),
                bucketRef
        ) && bucketAccessService.sameResource(
                bucketAccessService.qualify(other.getSpec().getAccessKeyRef(), otherNamespace),
                accessKeyRef
        );
    }

    private UpdateControl<GarageBucketAccess> previousGrantNotRevocable(
            GarageBucketAccess resource,
            GarageBucketAccessStatus status
    ) {
        return pending(
                resource,
                status,
                "The previous grant cannot be revoked yet, because its GarageClusterConnection does not exist or is not ready [connection=%s/%s]".formatted(
                        status.getConnectionNamespace(),
                        status.getConnectionName()
                )
        );
    }

    private UpdateControl<GarageBucketAccess> pending(
            GarageBucketAccess resource,
            GarageBucketAccessStatus status,
            String message
    ) {
        status.updatePhase(CRPhase.PENDING)
                .setMessage(message);

        // Only a safety net; the `GarageBucket` and `GarageAccessKey` watches normally trigger far sooner.
        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
    }
}
