package it.aboutbits.garage.crd.garagebucketaccess;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Categories;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import it.aboutbits.garage.core.Constants;
import it.aboutbits.garage.core.Named;
import org.jspecify.annotations.NullMarked;

import java.util.stream.Collectors;

/// Grants an [it.aboutbits.garage.crd.garageaccesskey.GarageAccessKey] permissions on a
/// [it.aboutbits.garage.crd.garagebucket.GarageBucket]; on its own a key has no access to anything.
@Version(Constants.CRD_VERSION)
@Group(Constants.CRD_GROUP)
@Categories(Constants.CRD_CATEGORY)
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
        name = "Bucket",
        jsonPath = ".spec.bucketRef.name",
        type = AdditionalPrinterColumn.Type.STRING
)
@AdditionalPrinterColumn(
        name = "Access Key",
        jsonPath = ".spec.accessKeyRef.name",
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
public class GarageBucketAccess
        extends CustomResource<GarageBucketAccessSpec, GarageBucketAccessStatus>
        implements Namespaced, Named {
    @Override
    @JsonIgnore
    public String getName() {
        var spec = getSpec();

        return "%s -> %s [%s]".formatted(
                spec.getAccessKeyRef().getName(),
                spec.getBucketRef().getName(),
                spec.getPermissions().stream()
                        .map(BucketPermission::toValue)
                        .collect(Collectors.joining(","))
        );
    }
}
