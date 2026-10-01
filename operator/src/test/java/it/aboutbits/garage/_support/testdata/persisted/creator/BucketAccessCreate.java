package it.aboutbits.garage._support.testdata.persisted.creator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.crd.bucketaccess.BucketAccess;
import it.aboutbits.garage.crd.bucketaccess.BucketAccessSpec;
import it.aboutbits.garage.crd.bucketaccess.BucketPermission;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/// Creates a [BucketAccess]. Unlike the other creators, the bucket and access key references must
/// be given explicitly: a grant is only meaningful between two resources the test set up itself.
@Setter
@Accessors(fluent = true, chain = true)
@NullMarked
public class BucketAccessCreate extends TestDataCreator<BucketAccess> {
    private final KubernetesClient kubernetesClient;

    private @Nullable String withNamespace;

    @Setter(AccessLevel.NONE)
    private boolean withoutNamespace = false;

    private @Nullable String withName;

    private @Nullable ResourceRef withBucketRef;

    private @Nullable ResourceRef withAccessKeyRef;

    private @Nullable List<BucketPermission> withPermissions;

    public BucketAccessCreate(
            int numberOfItems,
            KubernetesClient kubernetesClient
    ) {
        super(numberOfItems);
        this.kubernetesClient = kubernetesClient;
    }

    public BucketAccessCreate withoutNamespace() {
        this.withoutNamespace = true;
        return this;
    }

    public BucketAccessCreate withPermissions(BucketPermission... permissions) {
        this.withPermissions = List.of(permissions);
        return this;
    }

    @Override
    protected BucketAccess create(int index) {
        var namespace = getNamespace();
        var name = getName();

        var item = new BucketAccess();

        item.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .build()
        );

        var spec = new BucketAccessSpec();

        spec.setBucketRef(Objects.requireNonNull(withBucketRef, "withBucketRef is required"));
        spec.setAccessKeyRef(Objects.requireNonNull(withAccessKeyRef, "withAccessKeyRef is required"));
        spec.setPermissions(getPermissions());

        item.setSpec(spec);

        kubernetesClient.resources(BucketAccess.class)
                .inNamespace(namespace)
                .resource(item)
                .serverSideApply();

        //noinspection ConstantConditions
        return kubernetesClient.resources(BucketAccess.class)
                .inNamespace(namespace)
                .withName(name)
                .waitUntilCondition(
                        bucketAccess -> bucketAccess != null && bucketAccess.getStatus() != null,
                        30,
                        TimeUnit.SECONDS
                );
    }

    private @Nullable String getNamespace() {
        if (withoutNamespace) {
            return null;
        }

        if (withNamespace != null) {
            return withNamespace;
        }

        withNamespace = kubernetesClient.getNamespace();

        return withNamespace;
    }

    private String getName() {
        if (withName != null) {
            return withName;
        }

        withName = randomKubernetesNameSuffix("test-bucket-access");

        return withName;
    }

    private List<BucketPermission> getPermissions() {
        if (withPermissions != null) {
            return withPermissions;
        }

        withPermissions = List.of(BucketPermission.READ);

        return withPermissions;
    }
}
