package it.aboutbits.garage.core;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@NullMarked
public abstract class BaseReconciler<CR extends CustomResource<?, S> & Named, S extends CRStatus> {
    /// Bounds how long backend drift, which Kubernetes sees no events for, goes unnoticed.
    public static final long RESYNC_INTERVAL_MINUTES = 10;

    protected abstract S newStatus();

    public S initializeStatus(CR resource) {
        S status = resource.getStatus();

        //noinspection ConstantConditions
        if (status == null) {
            status = newStatus();
            resource.setStatus(status);
        }

        status.setName(resource.getName());
        status.setLastProbeTime(OffsetDateTime.now(ZoneOffset.UTC));
        status.setObservedGeneration(resource.getMetadata().getGeneration());

        return status;
    }

    public String getResourceNamespaceOrOwn(
            CR resource,
            @Nullable String resourceNamespace
    ) {
        if (resourceNamespace != null) {
            return resourceNamespace;
        }

        return resource.getMetadata().getNamespace();
    }

    /// An empty result means "not yet", not "broken": callers report [CRPhase#PENDING] and retry.
    public Optional<S3Connection> getReferencedS3Connection(
            KubernetesClient kubernetesClient,
            CR resource,
            ResourceRef connectionRef
    ) {
        var connectionName = connectionRef.getName();
        var connectionNamespace = getResourceNamespaceOrOwn(resource, connectionRef.getNamespace());

        var s3Connection = kubernetesClient.resources(S3Connection.class)
                .inNamespace(connectionNamespace)
                .withName(connectionName)
                .get();

        //noinspection ConstantConditions
        if (s3Connection == null) {
            log.error(
                    "The specified S3Connection does not exist [resource={}/{}]",
                    connectionNamespace,
                    connectionName
            );

            return Optional.empty();
        }

        var status = s3Connection.getStatus();

        // No status yet means the connection has not been probed yet.
        //noinspection ConstantConditions
        if (status == null) {
            log.warn(
                    "The specified S3Connection has not been probed yet [resource={}/{}]",
                    connectionNamespace,
                    connectionName
            );

            return Optional.empty();
        }

        var currentPhase = status.getPhase();
        var expectedPhase = CRPhase.READY;

        if (!Objects.equals(currentPhase, expectedPhase)) {
            log.warn(
                    "The specified S3Connection is not ready yet [resource={}/{}, status.phase={}]",
                    connectionNamespace,
                    connectionName,
                    currentPhase
            );

            return Optional.empty();
        }

        // READY of an older generation says nothing about the current spec.
        if (status.getObservedGeneration() != s3Connection.getMetadata().getGeneration()) {
            log.warn(
                    "The specified S3Connection changed and has not been probed again yet [resource={}/{}, status.observedGeneration={}, metadata.generation={}]",
                    connectionNamespace,
                    connectionName,
                    status.getObservedGeneration(),
                    s3Connection.getMetadata().getGeneration()
            );

            return Optional.empty();
        }

        return Optional.of(s3Connection);
    }

    public <E extends Exception> UpdateControl<CR> handleError(
            CR resource,
            S status,
            E exception
    ) {
        log.error(
                "Failed to reconcile resource [resource={}]",
                resource.getMetadata().getName(),
                exception
        );

        status.setPhase(CRPhase.ERROR)
                .setMessage(exception.getMessage());

        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(60, TimeUnit.SECONDS);
    }
}
