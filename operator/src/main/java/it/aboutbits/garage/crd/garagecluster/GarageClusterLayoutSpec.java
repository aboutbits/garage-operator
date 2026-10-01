package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// A fresh Garage node serves no S3 traffic until it is assigned a role in a layout.
@Getter
@Setter
@NullMarked
public class GarageClusterLayoutSpec {
    /// The CRD schema carries no defaults, so it has to be spelled out (e.g. `default`).
    @Required
    @ValidationRule(
            value = "self.trim().size() > 0",
            message = "The zone must not be empty."
    )
    private String zone = "default";

    /// Should match the data volume size. Garage uses it as a weight, not as a limit.
    @Required
    @ValidationRule(
            value = "self.trim().size() > 0",
            message = "The capacity must not be empty."
    )
    @ValidationRule(
            value = "self.matches('^[0-9]+(\\\\.[0-9]+)?(E|P|T|G|M|k|Ei|Pi|Ti|Gi|Mi|Ki)?$')",
            message = "The capacity must be a Kubernetes quantity, for example '20Gi'."
    )
    private String capacity = "";

    @io.fabric8.generator.annotation.Nullable
    private @Nullable List<String> tags = new ArrayList<>();

    public List<String> getTags() {
        return tags != null
                ? tags
                : List.of();
    }
}
