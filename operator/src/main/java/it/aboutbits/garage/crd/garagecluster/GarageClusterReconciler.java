package it.aboutbits.garage.crd.garagecluster;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.MaxReconciliationInterval;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkiverse.operatorsdk.annotations.AdditionalRBACRules;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import it.aboutbits.garage.core.BaseReconciler;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.adminapi.GarageAdminApi;
import it.aboutbits.garage.core.adminapi.GarageAdminClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.util.concurrent.TimeUnit;

/// Deliberately no [io.javaoperatorsdk.operator.api.reconciler.Cleaner]: tearing down the layout would
/// trigger a rebalance and, on a single node, discard the data.
@Slf4j
@AdditionalRBACRules({
        @RBACRule(
                apiGroups = {""},
                resources = {"secrets"},
                verbs = {"get", "list", "watch"}
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
public class GarageClusterReconciler
        extends BaseReconciler<GarageCluster, GarageClusterStatus>
        implements Reconciler<GarageCluster> {
    /// While Garage is being installed, and while a node hands its data over.
    private static final long FOLLOW_UP_SECONDS = 15;

    private final GarageClusterService garageClusterService;
    private final GarageAdminClientFactory garageAdminClientFactory;

    @Override
    public UpdateControl<GarageCluster> reconcile(
            GarageCluster resource,
            Context<GarageCluster> context
    ) {
        var status = initializeStatus(resource);

        // Only `ready()` reads the health, so that other outcomes do not show an old value.
        status.setHealth(null)
                .setStorageNodes(0)
                .setStorageNodesUp(0);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Reconciling GarageCluster [resource={}/{}, status.phase={}]",
                namespace,
                name,
                status.getPhase()
        );

        try {
            var garageAdminApi = garageAdminClientFactory.create(resource);

            var result = garageClusterService.reconcileLayout(
                    garageAdminApi,
                    resource
            );

            // Keep the recorded nodes while Garage reports none, so that no second GarageCluster can claim them meanwhile.
            if (!result.nodes().isEmpty()) {
                status.setNodes(result.nodes())
                        .setNodeId(null);
            }

            status.setLayoutVersion(result.layoutVersion());

            return switch (result.outcome()) {
                case AWAITING_NODES, OUTDATED_NODE -> awaitingCluster(resource, status, result);
                case REJECTED -> rejected(resource, status, result);
                case UP_TO_DATE, APPLIED -> ready(resource, status, result, garageAdminApi);
            };
        } catch (Exception e) {
            return handleError(
                    resource,
                    status,
                    e
            );
        }
    }

    @Override
    protected GarageClusterStatus newStatus() {
        return new GarageClusterStatus();
    }

    /// Normal while Garage is being installed, so retried quickly rather than treated as an error.
    private UpdateControl<GarageCluster> awaitingCluster(
            GarageCluster resource,
            GarageClusterStatus status,
            LayoutReconcileResult result
    ) {
        log.info(
                "Waiting for the Garage cluster to become available [resource={}/{}, outcome={}]",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName(),
                result.outcome()
        );

        status.updatePhase(CRPhase.PENDING)
                .setMessage(result.message());

        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(FOLLOW_UP_SECONDS, TimeUnit.SECONDS);
    }

    /// Retried, since a node that connects later can make the layout valid.
    private UpdateControl<GarageCluster> rejected(
            GarageCluster resource,
            GarageClusterStatus status,
            LayoutReconcileResult result
    ) {
        log.error(
                "Garage rejected the cluster layout [resource={}/{}, message={}]",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName(),
                result.message()
        );

        status.updatePhase(CRPhase.ERROR)
                .setMessage(result.message());

        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(RETRY_SECONDS, TimeUnit.SECONDS);
    }

    private UpdateControl<GarageCluster> ready(
            GarageCluster resource,
            GarageClusterStatus status,
            LayoutReconcileResult result,
            GarageAdminApi garageAdminApi
    ) {
        var health = garageAdminApi.getClusterHealth();

        status.setHealth(health.status())
                .setStorageNodes(health.storageNodes())
                .setStorageNodesUp(health.storageNodesUp());

        status.updatePhase(CRPhase.READY)
                .setMessage(result.message());

        // `status.nodes` tells when a removed node can be shut down, so it follows Garage closely until then.
        if (status.getNodes().stream().anyMatch(GarageClusterNodeStatus::isDraining)) {
            return UpdateControl.patchStatus(resource)
                    .rescheduleAfter(FOLLOW_UP_SECONDS, TimeUnit.SECONDS);
        }

        return UpdateControl.patchStatus(resource);
    }
}
