package it.aboutbits.garage.crd.accesskey;

import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import it.aboutbits.garage.core.ResourceRef;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Getter
@Setter
@NullMarked
public class AccessKeySpec {
    @Required
    @ValidationRule(
            value = "self == oldSelf",
            message = "The connectionRef is immutable. Delete and recreate the AccessKey to move it to a different S3Connection."
    )
    private ResourceRef connectionRef = new ResourceRef();

    /// The name the key is created under on the backend.
    @Required
    @Max(63)
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
    @Max(253)
    private @Nullable String secretName = null;

    private boolean allowCreateBucket = false;
}
