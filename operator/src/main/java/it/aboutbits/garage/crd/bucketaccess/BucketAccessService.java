package it.aboutbits.garage.crd.bucketaccess;

import io.fabric8.kubernetes.api.model.HasMetadata;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.core.adminapi.BucketPermissions;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NullMarked;

import java.util.Collection;
import java.util.Objects;

@Singleton
@NullMarked
public class BucketAccessService {
    /// The list is the complete set: an absent permission means revoke, not leave as it is.
    public BucketPermissions desiredPermissions(Collection<BucketPermission> permissions) {
        return new BucketPermissions(
                permissions.contains(BucketPermission.READ),
                permissions.contains(BucketPermission.WRITE),
                permissions.contains(BucketPermission.OWNER)
        );
    }

    /// An omitted namespace resolves against the resource holding the reference, e.g. a `Bucket`'s
    /// `connectionRef` against the `Bucket`'s namespace, not the grant's.
    public ResourceRef qualify(
            ResourceRef ref,
            String holderNamespace
    ) {
        var qualified = new ResourceRef();

        qualified.setName(ref.getName());
        qualified.setNamespace(
                ref.getNamespace() != null
                        ? ref.getNamespace()
                        : holderNamespace
        );

        return qualified;
    }

    /// Both references must already be [qualified][#qualify].
    public boolean sameResource(
            ResourceRef left,
            ResourceRef right
    ) {
        return Objects.equals(left.getNamespace(), right.getNamespace())
                && Objects.equals(left.getName(), right.getName());
    }

    public boolean refersTo(
            ResourceRef ref,
            HasMetadata holder,
            HasMetadata target
    ) {
        var qualified = qualify(ref, holder.getMetadata().getNamespace());

        return Objects.equals(qualified.getNamespace(), target.getMetadata().getNamespace())
                && Objects.equals(qualified.getName(), target.getMetadata().getName());
    }
}
