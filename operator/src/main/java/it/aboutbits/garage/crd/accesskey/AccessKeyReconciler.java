package it.aboutbits.garage.crd.accesskey;

import io.fabric8.kubernetes.api.model.Secret;
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
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.Mappers;
import io.quarkiverse.operatorsdk.annotations.AdditionalRBACRules;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import it.aboutbits.garage.core.BaseReconciler;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.KubernetesService;
import it.aboutbits.garage.core.adminapi.AccessKeyInfo;
import it.aboutbits.garage.core.adminapi.AccessKeyPermissions;
import it.aboutbits.garage.core.adminapi.GarageService;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/// No reclaim policy: deleting an `AccessKey` always deletes the key on the backend.
@Slf4j
@AdditionalRBACRules({
        @RBACRule(
                apiGroups = {""},
                resources = {"secrets"},
                // No delete: the Secret is garbage-collected through its owner reference.
                verbs = {"get", "list", "watch", "create", "update", "patch"}
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
public class AccessKeyReconciler
        extends BaseReconciler<AccessKey, AccessKeyStatus>
        implements Reconciler<AccessKey>, Cleaner<AccessKey> {
    private static final long RETRY_SECONDS = 60;

    public static final String SECRET_DATA_ACCESS_KEY_ID = "accessKeyId";
    public static final String SECRET_DATA_SECRET_ACCESS_KEY = "secretAccessKey";

    private final KubernetesService kubernetesService;

    private final KubernetesClient kubernetesClient;
    private final GarageService garageService;

    /// Restricted to the operator's `managed-by` label, so not every Secret in the cluster is cached.
    @Override
    public List<EventSource<?, AccessKey>> prepareEventSources(EventSourceContext<AccessKey> context) {
        var secretEventSource = new InformerEventSource<>(
                InformerEventSourceConfiguration.from(Secret.class, AccessKey.class)
                        .withName("secret")
                        .withLabelSelector("%s=%s".formatted(
                                KubernetesService.LABEL_MANAGED_BY,
                                KubernetesService.LABEL_MANAGED_BY_VALUE
                        ))
                        .withSecondaryToPrimaryMapper(Mappers.fromOwnerReferences(AccessKey.class))
                        .withNamespacesInheritedFromController()
                        .build(),
                context
        );

        return List.of(secretEventSource);
    }

    @Override
    public UpdateControl<AccessKey> reconcile(
            AccessKey resource,
            Context<AccessKey> context
    ) {
        var spec = resource.getSpec();
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Reconciling AccessKey [resource={}/{}, spec.name={}, status.phase={}]",
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
            AccessKey resource,
            Context<AccessKey> context
    ) {
        var spec = resource.getSpec();
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Deleting AccessKey [resource={}/{}, spec.name={}, status.phase={}]",
                namespace,
                name,
                spec.getName(),
                status.getPhase()
        );

        if (status.getPhase() != CRPhase.DELETING) {
            status.setPhase(CRPhase.DELETING)
                    .setMessage("Access key deletion in progress");

            context.getClient().resource(resource).patchStatus();

            return DeleteControl.noFinalizerRemoval()
                    .rescheduleAfter(100, TimeUnit.MILLISECONDS);
        }

        // Never reached the backend: delete directly, even if the S3Connection is missing.
        var accessKeyId = status.getAccessKeyId();

        if (accessKeyId == null) {
            log.info(
                    "The AccessKey never reached the backend, deleting the resource directly [resource={}/{}]",
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
                // Release without backend cleanup, otherwise the namespace deletion hangs forever.
                log.warn(
                        "The namespace is being deleted and the S3Connection is gone, releasing the AccessKey without deleting the key on the backend [resource={}/{}, accessKey.id={}]",
                        namespace,
                        name,
                        accessKeyId
                );

                return DeleteControl.defaultDelete();
            }

            status.setMessage("The specified S3Connection no longer exists or is not ready yet [resource=%s/%s]".formatted(
                    getResourceNamespaceOrOwn(resource, connectionRef.getNamespace()),
                    connectionRef.getName()
            ));

            context.getClient().resource(resource).patchStatus();

            return DeleteControl.noFinalizerRemoval()
                    .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
        }

        var s3Connection = s3ConnectionOptional.get();

        try {
            // By recorded id, never by name: key names are not unique.
            var accessKeyOptional = garageService.getAccessKey(s3Connection, accessKeyId);

            if (accessKeyOptional.isEmpty()) {
                log.info(
                        "The access key no longer exists on the backend, nothing to delete [resource={}/{}, accessKey.id={}]",
                        namespace,
                        name,
                        accessKeyId
                );

                return DeleteControl.defaultDelete();
            }

            garageService.deleteAccessKey(
                    s3Connection,
                    accessKeyId
            );

            return DeleteControl.defaultDelete();
        } catch (Exception e) {
            log.error(
                    "Failed to delete AccessKey [resource=%s/%s, spec.name=%s]".formatted(
                            namespace,
                            name,
                            spec.getName()
                    ),
                    e
            );

            status.setMessage("Deletion failed: %s".formatted(e.getMessage()));

            context.getClient().resource(resource).patchStatus();

            return DeleteControl.noFinalizerRemoval()
                    .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    protected AccessKeyStatus newStatus() {
        return new AccessKeyStatus();
    }

    private UpdateControl<AccessKey> reconcile(
            S3Connection s3Connection,
            AccessKey resource,
            AccessKeyStatus status
    ) {
        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        var spec = resource.getSpec();

        var desiredPermissions = new AccessKeyPermissions(spec.isAllowCreateBucket());

        var accessKeyOptional = findManagedAccessKey(
                s3Connection,
                resource,
                status
        );

        AccessKeyInfo accessKey;
        @Nullable String message;

        if (accessKeyOptional.isEmpty()) {
            log.info(
                    "Creating AccessKey [resource={}/{}, spec.name={}]",
                    namespace,
                    name,
                    spec.getName()
            );

            accessKey = garageService.createAccessKey(
                    s3Connection,
                    spec.getName(),
                    desiredPermissions
            );

            message = "Access key created";
        } else {
            accessKey = accessKeyOptional.get();

            message = null;
        }

        // Recorded before anything can fail: status is patched on errors too, so cleanup finds the key.
        status.setAccessKeyId(accessKey.accessKeyId());

        if (!accessKey.permissions().equals(desiredPermissions)) {
            log.info(
                    "Updating AccessKey permissions [resource={}/{}, spec.name={}]",
                    namespace,
                    name,
                    spec.getName()
            );

            accessKey = garageService.updateAccessKeyPermissions(
                    s3Connection,
                    accessKey.accessKeyId(),
                    desiredPermissions
            );

            message = "Access key permissions updated";
        }

        var secretName = secretName(resource);

        var secretMessage = reconcileSecret(
                resource,
                accessKey,
                secretName
        );

        status.setSecretName(secretName);

        status.setPhase(CRPhase.READY)
                .setMessage(secretMessage != null ? secretMessage : message);

        return UpdateControl.patchStatus(resource);
    }

    /// After the first bind the key is addressed by its recorded id; the name is only used to
    /// discover (or adopt) it the first time, or again if the recorded key was deleted.
    private Optional<AccessKeyInfo> findManagedAccessKey(
            S3Connection s3Connection,
            AccessKey resource,
            AccessKeyStatus status
    ) {
        var recordedAccessKeyId = status.getAccessKeyId();

        if (recordedAccessKeyId != null) {
            var recorded = garageService.getAccessKey(s3Connection, recordedAccessKeyId);

            if (recorded.isPresent()) {
                return recorded;
            }

            log.warn(
                    "The recorded access key no longer exists on the backend, looking it up by name [resource={}/{}, accessKey.id={}]",
                    resource.getMetadata().getNamespace(),
                    resource.getMetadata().getName(),
                    recordedAccessKeyId
            );
        }

        var found = garageService.findAccessKey(s3Connection, resource.getSpec().getName());

        found.ifPresent(accessKey -> requireNotManagedElsewhere(resource, accessKey.accessKeyId()));

        return found;
    }

    /// Refuse to adopt a key another `AccessKey` manages: deleting either would delete it for both.
    private void requireNotManagedElsewhere(
            AccessKey resource,
            String accessKeyId
    ) {
        var managedElsewhere = kubernetesClient.resources(AccessKey.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .filter(other -> !Objects.equals(other.getMetadata().getUid(), resource.getMetadata().getUid()))
                .filter(other -> other.getStatus() != null && accessKeyId.equals(other.getStatus().getAccessKeyId()))
                .findFirst();

        if (managedElsewhere.isPresent()) {
            var other = managedElsewhere.get();

            throw new IllegalStateException(
                    "The access key named %s is already managed by AccessKey %s/%s. Use a different spec.name [accessKey.id=%s]".formatted(
                            resource.getSpec().getName(),
                            other.getMetadata().getNamespace(),
                            other.getMetadata().getName(),
                            accessKeyId
                    )
            );
        }
    }

    private @Nullable String reconcileSecret(
            AccessKey resource,
            AccessKeyInfo accessKey,
            String secretName
    ) {
        var secretAccessKey = accessKey.secretAccessKey();

        if (secretAccessKey == null) {
            // The backend did not reveal the secret (Garage always does, so this is a real error).
            throw new IllegalStateException(
                    "The backend did not return the secret access key, so the Secret cannot be written [accessKeyId=%s]".formatted(
                            accessKey.accessKeyId()
                    )
            );
        }

        var changed = kubernetesService.upsertOwnedSecret(
                kubernetesClient,
                resource,
                secretName,
                Map.of(
                        SECRET_DATA_ACCESS_KEY_ID, accessKey.accessKeyId(),
                        SECRET_DATA_SECRET_ACCESS_KEY, secretAccessKey
                )
        );

        if (!changed) {
            return null;
        }

        log.info(
                "Wrote AccessKey credentials [resource={}/{}, secret.name={}]",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName(),
                secretName
        );

        return "Credentials written to Secret %s".formatted(secretName);
    }

    private String secretName(AccessKey resource) {
        var secretName = resource.getSpec().getSecretName();

        return secretName == null || secretName.isBlank()
                ? resource.getMetadata().getName()
                : secretName;
    }
}
