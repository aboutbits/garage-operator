package it.aboutbits.garage.crd.bucketaccess;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import it.aboutbits.garage.core.ResourceRef;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NullMarked
public class BucketAccessSpec {
    @Required
    @ValidationRule(
            value = "self == oldSelf",
            message = "The bucketRef is immutable. Delete and recreate the BucketAccess to grant access to a different Bucket."
    )
    private ResourceRef bucketRef = new ResourceRef();

    /// Must live on the same `S3Connection` as the bucket.
    @Required
    @ValidationRule(
            value = "self == oldSelf",
            message = "The accessKeyRef is immutable. Delete and recreate the BucketAccess to grant access to a different AccessKey."
    )
    private ResourceRef accessKeyRef = new ResourceRef();

    /// The complete desired set: anything not listed is revoked.
    @Required
    @ValidationRule(
            value = "self.size() > 0",
            message = "At least one permission must be granted. Delete the BucketAccess to revoke all access."
    )
    private List<BucketPermission> permissions = new ArrayList<>();
}
