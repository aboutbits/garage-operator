package it.aboutbits.garage.crd.garagecluster;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import it.aboutbits.garage.core.Named;
import org.jspecify.annotations.NullMarked;

/// A Garage cluster whose layout the operator manages; everything else is owned by the Helm chart.
@Version("v1")
@Group("garage.aboutbits.it")
@AdditionalPrinterColumn(
        name = "Name",
        jsonPath = ".status.name",
        type = AdditionalPrinterColumn.Type.STRING
)
@AdditionalPrinterColumn(
        name = "Phase",
        jsonPath = ".status.phase",
        type = AdditionalPrinterColumn.Type.STRING
)
@AdditionalPrinterColumn(
        name = "Layout",
        jsonPath = ".status.layoutVersion",
        type = AdditionalPrinterColumn.Type.INTEGER
)
@AdditionalPrinterColumn(
        name = "Health",
        jsonPath = ".status.health",
        type = AdditionalPrinterColumn.Type.STRING
)
@AdditionalPrinterColumn(
        name = "Message",
        jsonPath = ".status.message",
        type = AdditionalPrinterColumn.Type.STRING
)
@AdditionalPrinterColumn(
        name = "Since",
        jsonPath = ".status.lastPhaseTransitionTime",
        type = AdditionalPrinterColumn.Type.DATE
)
@AdditionalPrinterColumn(
        name = "Age",
        jsonPath = ".metadata.creationTimestamp",
        type = AdditionalPrinterColumn.Type.DATE
)
@NullMarked
public class GarageCluster
        extends CustomResource<GarageClusterSpec, GarageClusterStatus>
        implements Namespaced, Named {
    @Override
    @JsonIgnore
    public String getName() {
        return getSpec().getAdminEndpoint();
    }
}
