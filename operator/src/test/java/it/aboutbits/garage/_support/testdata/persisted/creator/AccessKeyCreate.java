package it.aboutbits.garage._support.testdata.persisted.creator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.crd.accesskey.AccessKey;
import it.aboutbits.garage.crd.accesskey.AccessKeySpec;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

@Setter
@Accessors(fluent = true, chain = true)
@NullMarked
public class AccessKeyCreate extends TestDataCreator<AccessKey> {
    private final Given given;

    private final KubernetesClient kubernetesClient;

    private @Nullable String withNamespace;

    private @Nullable String withName;

    private @Nullable String withKeyName;

    private @Nullable ResourceRef withConnectionRef;

    private @Nullable String withSecretName;

    private @Nullable Boolean withAllowCreateBucket;

    public AccessKeyCreate(
            int numberOfItems,
            Given given,
            KubernetesClient kubernetesClient
    ) {
        super(numberOfItems);
        this.given = given;
        this.kubernetesClient = kubernetesClient;
    }

    @Override
    protected AccessKey create(int index) {
        var namespace = getNamespace();
        var name = getName();

        var item = new AccessKey();

        item.setMetadata(new ObjectMetaBuilder()
                .withNamespace(namespace)
                .withName(name)
                .build()
        );

        var spec = new AccessKeySpec();

        spec.setConnectionRef(getConnectionRef());
        spec.setName(getKeyName());
        spec.setSecretName(withSecretName);
        spec.setAllowCreateBucket(getAllowCreateBucket());

        item.setSpec(spec);

        kubernetesClient.resources(AccessKey.class)
                .inNamespace(namespace)
                .resource(item)
                .serverSideApply();

        //noinspection ConstantConditions
        return kubernetesClient.resources(AccessKey.class)
                .inNamespace(namespace)
                .withName(name)
                .waitUntilCondition(
                        accessKey -> accessKey != null && accessKey.getStatus() != null,
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

        withName = randomKubernetesNameSuffix("test-access-key");

        return withName;
    }

    private String getKeyName() {
        if (withKeyName != null) {
            return withKeyName;
        }

        withKeyName = randomKubernetesNameSuffix("test-key");

        return withKeyName;
    }

    private ResourceRef getConnectionRef() {
        if (withConnectionRef != null) {
            return withConnectionRef;
        }

        var s3Connection = given.one()
                .s3Connection()
                .returnFirst();

        var connectionRef = new ResourceRef();

        connectionRef.setNamespace(s3Connection.getMetadata().getNamespace());
        connectionRef.setName(s3Connection.getMetadata().getName());

        withConnectionRef = connectionRef;

        return withConnectionRef;
    }

    private boolean getAllowCreateBucket() {
        if (withAllowCreateBucket != null) {
            return withAllowCreateBucket;
        }

        withAllowCreateBucket = false;

        return withAllowCreateBucket;
    }
}
