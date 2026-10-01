package it.aboutbits.garage._support.testdata.persisted.creator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.SecretKeyRef;
import it.aboutbits.garage.crd.garagecluster.GarageCluster;
import it.aboutbits.garage.crd.garagecluster.GarageClusterLayoutSpec;
import it.aboutbits.garage.crd.garagecluster.GarageClusterSpec;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Setter
@Accessors(fluent = true, chain = true)
@NullMarked
public class GarageClusterCreate extends TestDataCreator<GarageCluster> {
    private final Given given;

    private final KubernetesClient kubernetesClient;
    private final Given.GarageConnectionDetails garageConnectionDetails;

    private @Nullable String withNamespace;

    @Setter(AccessLevel.NONE)
    private boolean withoutNamespace = false;

    private @Nullable String withName;

    private @Nullable String withAdminEndpoint;

    private @Nullable SecretKeyRef withAdminSecretRef;

    private @Nullable String withZone;

    private @Nullable String withCapacity;

    private @Nullable List<String> withTags;

    public GarageClusterCreate(
            int numberOfItems,
            Given given,
            KubernetesClient kubernetesClient,
            Given.GarageConnectionDetails garageConnectionDetails
    ) {
        super(numberOfItems);
        this.given = given;
        this.kubernetesClient = kubernetesClient;
        this.garageConnectionDetails = garageConnectionDetails;
    }

    public GarageClusterCreate withoutNamespace() {
        this.withoutNamespace = true;
        return this;
    }

    @Override
    protected GarageCluster create(int index) {
        var namespace = getNamespace();
        var name = getName();

        var item = new GarageCluster();

        item.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .build()
        );

        var layout = new GarageClusterLayoutSpec();

        layout.setZone(getZone());
        layout.setCapacity(getCapacity());
        layout.setTags(getTags());

        var spec = new GarageClusterSpec();

        spec.setAdminEndpoint(getAdminEndpoint());
        spec.setAdminSecretRef(getAdminSecretRef());
        spec.setLayout(layout);

        item.setSpec(spec);

        kubernetesClient.resources(GarageCluster.class)
                .inNamespace(namespace)
                .resource(item)
                .serverSideApply();

        //noinspection ConstantConditions
        return kubernetesClient.resources(GarageCluster.class)
                .inNamespace(namespace)
                .withName(name)
                .waitUntilCondition(
                        garageCluster -> garageCluster != null && garageCluster.getStatus() != null,
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

        withName = randomKubernetesNameSuffix("test-garage-cluster");

        return withName;
    }

    private String getAdminEndpoint() {
        if (withAdminEndpoint != null) {
            return withAdminEndpoint;
        }

        withAdminEndpoint = garageConnectionDetails.adminEndpoint();

        return withAdminEndpoint;
    }

    private SecretKeyRef getAdminSecretRef() {
        if (withAdminSecretRef != null) {
            return withAdminSecretRef;
        }

        return given.one()
                .secretKeyRef()
                .withToken(garageConnectionDetails.adminToken())
                .returnFirst();
    }

    private String getZone() {
        if (withZone != null) {
            return withZone;
        }

        withZone = "default";

        return withZone;
    }

    private String getCapacity() {
        if (withCapacity != null) {
            return withCapacity;
        }

        withCapacity = "20Gi";

        return withCapacity;
    }

    private List<String> getTags() {
        if (withTags != null) {
            return withTags;
        }

        withTags = List.of();

        return withTags;
    }
}
