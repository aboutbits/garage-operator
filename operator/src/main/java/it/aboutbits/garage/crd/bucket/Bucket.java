package it.aboutbits.garage.crd.bucket;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import it.aboutbits.garage.core.Named;
import org.jspecify.annotations.NullMarked;

/// A bucket on the backend named by its [BucketSpec#getConnectionRef].
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
        name = "Objects",
        jsonPath = ".status.objects",
        type = AdditionalPrinterColumn.Type.INTEGER
)
@AdditionalPrinterColumn(
        name = "Bytes",
        jsonPath = ".status.bytes",
        type = AdditionalPrinterColumn.Type.INTEGER
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
public class Bucket
        extends CustomResource<BucketSpec, BucketStatus>
        implements Namespaced, Named {
    @Override
    public String getName() {
        return getSpec().getName();
    }
}
