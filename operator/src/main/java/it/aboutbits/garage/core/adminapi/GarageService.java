package it.aboutbits.garage.core.adminapi;

import it.aboutbits.garage.crd.s3connection.S3Connection;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;

/// Drives Garage through its Admin API v2.
@ApplicationScoped
@RequiredArgsConstructor
@NullMarked
public class GarageService {
    /// Includes a node that has no cluster layout applied yet.
    static final String HEALTH_STATUS_UNAVAILABLE = "unavailable";

    private final GarageAdminClientFactory garageAdminClientFactory;

    public ConnectionProbeResult probe(S3Connection s3Connection) {
        var health = garageAdminClientFactory.create(s3Connection)
                .getClusterHealth();

        var detail = "Garage cluster %s [storageNodes=%d, storageNodesUp=%d, partitionsAllOk=%d/%d]".formatted(
                health.status(),
                health.storageNodes(),
                health.storageNodesUp(),
                health.partitionsAllOk(),
                health.partitions()
        );

        // `degraded` still serves reads and writes, so only `unavailable` counts as not ready.
        if (HEALTH_STATUS_UNAVAILABLE.equals(health.status())) {
            return ConnectionProbeResult.unavailable(
                    "%s. The cluster cannot serve requests — check that a GarageCluster has applied a layout.".formatted(detail)
            );
        }

        return ConnectionProbeResult.available(detail);
    }
}
