package it.aboutbits.garage._support.testdata.persisted.creator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.SecretKeyRef;
import it.aboutbits.garage.crd.clusterconnection.ClusterConnection;
import it.aboutbits.garage.crd.clusterconnection.ClusterConnectionSpec;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

@Setter
@Accessors(fluent = true, chain = true)
@NullMarked
public class ClusterConnectionCreate extends TestDataCreator<ClusterConnection> {
    private final Given given;

    private final KubernetesClient kubernetesClient;
    private final Given.GarageConnectionDetails garageConnectionDetails;

    private @Nullable String withNamespace;

    private @Nullable String withName;

    private @Nullable String withAdminEndpoint;

    private @Nullable SecretKeyRef withAdminSecretRef;

    public ClusterConnectionCreate(
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

    @Override
    protected ClusterConnection create(int index) {
        var namespace = getNamespace();
        var name = getName();

        var item = new ClusterConnection();

        item.setMetadata(new ObjectMetaBuilder()
                .withNamespace(namespace)
                .withName(name)
                .build()
        );

        var spec = new ClusterConnectionSpec();

        spec.setAdminEndpoint(getAdminEndpoint());
        spec.setAdminSecretRef(getAdminSecretRef());

        item.setSpec(spec);

        kubernetesClient.resources(ClusterConnection.class)
                .inNamespace(namespace)
                .resource(item)
                .serverSideApply();

        //noinspection ConstantConditions
        return kubernetesClient.resources(ClusterConnection.class)
                .inNamespace(namespace)
                .withName(name)
                .waitUntilCondition(
                        clusterConnection -> clusterConnection != null && clusterConnection.getStatus() != null,
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

        withName = randomKubernetesNameSuffix("test-cluster-connection");

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
}
