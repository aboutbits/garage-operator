package it.aboutbits.garage.crd.accesskey;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import it.aboutbits.garage.core.Named;
import org.jspecify.annotations.NullMarked;

/// S3 credentials on a backend, published only through a Secret owned by this resource.
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
        name = "Access Key ID",
        jsonPath = ".status.accessKeyId",
        type = AdditionalPrinterColumn.Type.STRING
)
@AdditionalPrinterColumn(
        name = "Secret",
        jsonPath = ".status.secretName",
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
public class AccessKey
        extends CustomResource<AccessKeySpec, AccessKeyStatus>
        implements Namespaced, Named {
    @Override
    public String getName() {
        return getSpec().getName();
    }
}
