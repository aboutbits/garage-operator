package it.aboutbits.garage.crd.bucket;

import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import it.aboutbits.garage.core.ReclaimPolicy;
import it.aboutbits.garage.core.ResourceRef;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Getter
@Setter
// No @SchemaCustomizer: it would apply the Kubernetes name pattern to every field, including the S3 name.
@NullMarked
public class BucketSpec {
    @Required
    @ValidationRule(
            value = "self == oldSelf",
            message = "The connectionRef is immutable. Delete and recreate the Bucket to move it to a different S3Connection."
    )
    private ResourceRef connectionRef = new ResourceRef();

    /// The name the bucket is addressed by over S3.
    @Required
    @Max(63)
    @ValidationRule(
            value = "self == oldSelf",
            message = "The Bucket name is immutable. Delete and recreate the Bucket to use a different name."
    )
    @ValidationRule(
            value = "self.matches('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')",
            message = "The Bucket name must be 3-63 characters of lowercase letters, digits, dots or hyphens, and must start and end with a letter or digit."
    )
    private String name = "";

    /// A bucket that still holds objects is never deleted, whatever this is set to.
    @io.fabric8.generator.annotation.Nullable
    private ReclaimPolicy reclaimPolicy = ReclaimPolicy.RETAIN;

    /// Omit for an unlimited bucket.
    @io.fabric8.generator.annotation.Nullable
    private @Nullable BucketQuotasSpec quotas = null;
}
