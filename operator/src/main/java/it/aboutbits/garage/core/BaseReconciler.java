package it.aboutbits.garage.core;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import it.aboutbits.garage.crd.garageclusterconnection.GarageClusterConnection;
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
    public static final long RETRY_SECONDS = 60;

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
    public Optional<GarageClusterConnection> getReferencedGarageClusterConnection(
            KubernetesClient kubernetesClient,
            CR resource,
            ResourceRef connectionRef
    ) {
        var connectionName = connectionRef.getName();
        var connectionNamespace = getResourceNamespaceOrOwn(resource, connectionRef.getNamespace());

        var garageClusterConnection = kubernetesClient.resources(GarageClusterConnection.class)
                .inNamespace(connectionNamespace)
                .withName(connectionName)
                .get();

        //noinspection ConstantConditions
        if (garageClusterConnection == null) {
            log.error(
                    "The specified GarageClusterConnection does not exist [spec.connectionRef={}/{}]",
                    connectionNamespace,
                    connectionName
            );

            return Optional.empty();
        }

        var status = garageClusterConnection.getStatus();

        // No status yet means the connection has not been probed yet.
        //noinspection ConstantConditions
        if (status == null) {
            log.warn(
                    "The specified GarageClusterConnection has not been probed yet [spec.connectionRef={}/{}]",
                    connectionNamespace,
                    connectionName
            );

            return Optional.empty();
        }

        var currentPhase = status.getPhase();
        var expectedPhase = CRPhase.READY;

        if (!Objects.equals(currentPhase, expectedPhase)) {
            log.warn(
                    "The specified GarageClusterConnection is not ready yet [spec.connectionRef={}/{}, status.phase={}]",
                    connectionNamespace,
                    connectionName,
                    currentPhase
            );

            return Optional.empty();
        }

        // READY of an older generation says nothing about the current spec.
        if (status.getObservedGeneration() != garageClusterConnection.getMetadata().getGeneration()) {
            log.warn(
                    "The specified GarageClusterConnection changed and has not been probed again yet [spec.connectionRef={}/{}, status.observedGeneration={}, metadata.generation={}]",
                    connectionNamespace,
                    connectionName,
                    status.getObservedGeneration(),
                    garageClusterConnection.getMetadata().getGeneration()
            );

            return Optional.empty();
        }

        return Optional.of(garageClusterConnection);
    }

    /// During namespace deletion the backend, its Secret and the [GarageClusterConnection] disappear at once,
    /// so cleanup waiting for them would block the namespace in `Terminating` forever.
    public boolean isNamespaceTerminating(
            KubernetesClient kubernetesClient,
            CR resource
    ) {
        var namespace = kubernetesClient.namespaces()
                .withName(resource.getMetadata().getNamespace())
                .get();

        //noinspection ConstantConditions
        return namespace == null || namespace.getMetadata().getDeletionTimestamp() != null;
    }

    public <E extends Exception> UpdateControl<CR> handleError(
            CR resource,
            S status,
            E exception
    ) {
        log.error(
                "Failed to reconcile resource [resource={}/{}]",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName(),
                exception
        );

        status.updatePhase(CRPhase.ERROR)
                .setMessage(exception.getMessage());

        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
    }

    protected DeleteControl failCleanup(
            CR resource,
            S status,
            Context<CR> context,
            String message
    ) {
        status.setMessage(message);

        context.getClient().resource(resource).patchStatus();

        return DeleteControl.noFinalizerRemoval()
                .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
    }
}
