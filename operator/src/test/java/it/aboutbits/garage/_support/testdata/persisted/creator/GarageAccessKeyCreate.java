package it.aboutbits.garage._support.testdata.persisted.creator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.ResourceRef;
import it.aboutbits.garage.crd.garageaccesskey.GarageAccessKey;
import it.aboutbits.garage.crd.garageaccesskey.GarageAccessKeySpec;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

@Setter
@Accessors(fluent = true, chain = true)
@NullMarked
public class GarageAccessKeyCreate extends TestDataCreator<GarageAccessKey> {
    private final Given given;

    private final KubernetesClient kubernetesClient;

    private @Nullable String withNamespace;

    private @Nullable String withName;

    private @Nullable String withKeyName;

    private @Nullable ResourceRef withConnectionRef;

    private @Nullable String withSecretName;

    private @Nullable Boolean withAllowCreateBucket;

    public GarageAccessKeyCreate(
            int numberOfItems,
            Given given,
            KubernetesClient kubernetesClient
    ) {
        super(numberOfItems);
        this.given = given;
        this.kubernetesClient = kubernetesClient;
    }

    @Override
    protected GarageAccessKey create(int index) {
        var namespace = getNamespace();
        var name = getName();

        var item = new GarageAccessKey();

        item.setMetadata(new ObjectMetaBuilder()
                .withNamespace(namespace)
                .withName(name)
                .build()
        );

        var spec = new GarageAccessKeySpec();

        spec.setConnectionRef(getConnectionRef());
        spec.setName(getKeyName());
        spec.setSecretName(withSecretName);
        spec.setAllowCreateBucket(getAllowCreateBucket());

        item.setSpec(spec);

        kubernetesClient.resources(GarageAccessKey.class)
                .inNamespace(namespace)
                .resource(item)
                .serverSideApply();

        //noinspection ConstantConditions
        return kubernetesClient.resources(GarageAccessKey.class)
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

        var garageClusterConnection = given.one()
                .garageClusterConnection()
                .returnFirst();

        var connectionRef = new ResourceRef();

        connectionRef.setNamespace(garageClusterConnection.getMetadata().getNamespace());
        connectionRef.setName(garageClusterConnection.getMetadata().getName());

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
