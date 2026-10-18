package it.aboutbits.garage.crd.garagebucket;

import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.ValidationRule;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Omitting a field means no limit; clearing a previously set field removes the limit on the backend.
@Getter
@Setter
@NullMarked
public class GarageBucketQuotasSpec {
    /// Total size of the objects, as a Kubernetes quantity such as `10Gi`.
    @io.fabric8.generator.annotation.Nullable
    @ValidationRule(
            value = "isQuantity(self) && quantity(self).isInteger() && quantity(self).isGreaterThan(quantity('0'))",
            message = "The maxSize must be a Kubernetes quantity of whole bytes, greater than zero, for example '10Gi'."
    )
    private @Nullable String maxSize = null;

    @io.fabric8.generator.annotation.Nullable
    @Min(1)
    private @Nullable Long maxObjects = null;
}
