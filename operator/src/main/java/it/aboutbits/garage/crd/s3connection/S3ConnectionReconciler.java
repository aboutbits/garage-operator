package it.aboutbits.garage.crd.s3connection;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.MaxReconciliationInterval;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkiverse.operatorsdk.annotations.AdditionalRBACRules;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import it.aboutbits.garage.core.BaseReconciler;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.CRStatus;
import it.aboutbits.garage.core.adminapi.GarageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.util.concurrent.TimeUnit;

/// Only probes, nothing is created here. Dependent resources gate on the connection being `READY`.
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
public class S3ConnectionReconciler
        extends BaseReconciler<S3Connection, CRStatus>
        implements Reconciler<S3Connection> {
    private static final long UNAVAILABLE_RETRY_SECONDS = 30;

    private final GarageService garageService;

    @Override
    public UpdateControl<S3Connection> reconcile(
            S3Connection resource,
            Context<S3Connection> context
    ) {
        var status = initializeStatus(resource);

        var namespace = resource.getMetadata().getNamespace();
        var name = resource.getMetadata().getName();

        log.info(
                "Reconciling S3Connection [resource={}/{}, status.phase={}]",
                namespace,
                name,
                status.getPhase()
        );

        try {
            var probe = garageService.probe(resource);

            if (!probe.available()) {
                log.warn(
                        "The backend is reachable but not ready yet [resource={}/{}, detail={}]",
                        namespace,
                        name,
                        probe.detail()
                );

                status.setPhase(CRPhase.PENDING)
                        .setMessage(probe.detail());

                return UpdateControl.patchStatus(resource)
                        .rescheduleAfter(UNAVAILABLE_RETRY_SECONDS, TimeUnit.SECONDS);
            }

            status.setPhase(CRPhase.READY)
                    .setMessage(probe.detail());

            return UpdateControl.patchStatus(resource);
        } catch (Exception e) {
            return handleError(
                    resource,
                    status,
                    e
            );
        }
    }

    @Override
    protected CRStatus newStatus() {
        return new CRStatus();
    }
}
