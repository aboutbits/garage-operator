package it.aboutbits.garage._support.testdata.persisted.creator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.ReclaimPolicy;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.crd.bucket.Bucket;
import it.aboutbits.garage.crd.bucket.BucketQuotasSpec;
import it.aboutbits.garage.crd.bucket.BucketSpec;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

@Setter
@Accessors(fluent = true, chain = true)
@NullMarked
public class BucketCreate extends TestDataCreator<Bucket> {
    private final Given given;

    private final KubernetesClient kubernetesClient;

    private @Nullable String withNamespace;

    private @Nullable String withName;

    private @Nullable String withBucketName;

    private @Nullable ResourceRef withConnectionRef;

    private @Nullable ReclaimPolicy withReclaimPolicy;

    private @Nullable String withMaxSize;

    private @Nullable Long withMaxObjects;

    public BucketCreate(
            int numberOfItems,
            Given given,
            KubernetesClient kubernetesClient
    ) {
        super(numberOfItems);
        this.given = given;
        this.kubernetesClient = kubernetesClient;
    }

    /// Leave the quotas section out of the spec entirely.
    public BucketCreate withoutQuotas() {
        this.withMaxSize = null;
        this.withMaxObjects = null;
        return this;
    }

    @Override
    protected Bucket create(int index) {
        var namespace = getNamespace();
        var name = getName();

        var item = new Bucket();

        item.setMetadata(new ObjectMetaBuilder()
                .withNamespace(namespace)
                .withName(name)
                .build()
        );

        var spec = new BucketSpec();

        spec.setConnectionRef(getConnectionRef());
        spec.setName(getBucketName());
        spec.setReclaimPolicy(getReclaimPolicy());
        spec.setQuotas(getQuotas());

        item.setSpec(spec);

        kubernetesClient.resources(Bucket.class)
                .inNamespace(namespace)
                .resource(item)
                .serverSideApply();

        //noinspection ConstantConditions
        return kubernetesClient.resources(Bucket.class)
                .inNamespace(namespace)
                .withName(name)
                .waitUntilCondition(
                        bucket -> bucket != null && bucket.getStatus() != null,
                        30,
                        TimeUnit.SECONDS
                );
    }

    private String getNamespace() {
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

        withName = randomKubernetesNameSuffix("test-bucket");

        return withName;
    }

    private String getBucketName() {
        if (withBucketName != null) {
            return withBucketName;
        }

        withBucketName = randomKubernetesNameSuffix("test-bucket");

        return withBucketName;
    }

    private ResourceRef getConnectionRef() {
        if (withConnectionRef != null) {
            return withConnectionRef;
        }

        var clusterConnection = given.one()
                .clusterConnection()
                .returnFirst();

        var connectionRef = new ResourceRef();

        connectionRef.setNamespace(clusterConnection.getMetadata().getNamespace());
        connectionRef.setName(clusterConnection.getMetadata().getName());

        withConnectionRef = connectionRef;

        return withConnectionRef;
    }

    private ReclaimPolicy getReclaimPolicy() {
        if (withReclaimPolicy != null) {
            return withReclaimPolicy;
        }

        withReclaimPolicy = ReclaimPolicy.RETAIN;

        return withReclaimPolicy;
    }

    private @Nullable BucketQuotasSpec getQuotas() {
        if (withMaxSize == null && withMaxObjects == null) {
            return null;
        }

        var quotas = new BucketQuotasSpec();

        quotas.setMaxSize(withMaxSize);
        quotas.setMaxObjects(withMaxObjects);

        return quotas;
    }
}
