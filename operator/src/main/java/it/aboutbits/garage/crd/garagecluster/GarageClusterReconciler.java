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
    private static final long BOOTSTRAP_RETRY_SECONDS = 15;

    private final GarageClusterService garageClusterService;
    private final GarageAdminClientFactory garageAdminClientFactory;

    @Override
    public UpdateControl<GarageCluster> reconcile(
            GarageCluster resource,
            Context<GarageCluster> context
    ) {
        var status = initializeStatus(resource);

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
                    resource.getSpec().getLayout()
            );

            status.setNodeId(result.nodeId())
                    .setLayoutVersion(result.layoutVersion());

            return switch (result.outcome()) {
                case NO_NODES, NODE_DOWN -> awaitingCluster(resource, status, result);
                case UNSUPPORTED_TOPOLOGY -> unsupportedTopology(resource, status, result);
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

    /// Normal right after a `helm install`, so retried quickly rather than treated as an error.
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

        status.setPhase(CRPhase.PENDING)
                .setMessage(result.message());

        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(BOOTSTRAP_RETRY_SECONDS, TimeUnit.SECONDS);
    }

    /// An ERROR, since retrying will not fix it.
    private UpdateControl<GarageCluster> unsupportedTopology(
            GarageCluster resource,
            GarageClusterStatus status,
            LayoutReconcileResult result
    ) {
        log.error(
                "Refusing to manage the cluster layout [resource={}/{}, message={}]",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName(),
                result.message()
        );

        status.setPhase(CRPhase.ERROR)
                .setMessage(result.message());

        return UpdateControl.patchStatus(resource);
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

        status.setPhase(CRPhase.READY)
                .setMessage(result.message());

        return UpdateControl.patchStatus(resource);
    }
}
