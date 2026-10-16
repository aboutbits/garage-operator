package it.aboutbits.garage.crd.accesskey;

import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import it.aboutbits.garage.core.ResourceRef;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Getter
@Setter
// The rule on secretName is skipped when the field is absent before or after, so setting or removing it is checked here.
@ValidationRule(
        value = "has(self.secretName) == has(oldSelf.secretName)",
        message = "The secretName is immutable. Delete and recreate the AccessKey to use a different Secret name."
)
@NullMarked
public class AccessKeySpec {
    @Required
    @ValidationRule(
            value = "self == oldSelf",
            message = "The connectionRef is immutable. Delete and recreate the AccessKey to move it to a different ClusterConnection."
    )
    private ResourceRef connectionRef = new ResourceRef();

    /// The name the key is created under on the backend.
    @Required
    @Size(max = 63)
    @ValidationRule(
            value = "self == oldSelf",
            message = "The AccessKey name is immutable. Delete and recreate the AccessKey to use a different name."
    )
    @ValidationRule(
            value = "self.trim().size() > 0",
            message = "The AccessKey name must not be empty."
    )
    private String name = "";

    /// Defaults to `metadata.name`; always created in the AccessKey's own namespace.
    @io.fabric8.generator.annotation.Nullable
    @Size(max = 253)
    @Pattern(ResourceRef.DNS_SUBDOMAIN_PATTERN)
    @ValidationRule(
            value = "self == oldSelf",
            message = "The secretName is immutable. Delete and recreate the AccessKey to use a different Secret name."
    )
    private @Nullable String secretName = null;

    private boolean allowCreateBucket = false;
}
