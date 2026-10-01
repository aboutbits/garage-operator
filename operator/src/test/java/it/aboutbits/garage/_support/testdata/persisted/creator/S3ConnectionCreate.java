package it.aboutbits.garage._support.testdata.persisted.creator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage._support.testdata.persisted.Given;
import it.aboutbits.garage.core.SecretKeyRef;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import it.aboutbits.garage.crd.s3connection.S3ConnectionSpec;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

@Setter
@Accessors(fluent = true, chain = true)
@NullMarked
public class S3ConnectionCreate extends TestDataCreator<S3Connection> {
    private final Given given;

    private final KubernetesClient kubernetesClient;
    private final Given.GarageConnectionDetails garageConnectionDetails;

    private @Nullable String withNamespace;

    @Setter(AccessLevel.NONE)
    private boolean withoutNamespace = false;

    private @Nullable String withName;

    private @Nullable String withAdminEndpoint;

    private @Nullable SecretKeyRef withAdminSecretRef;

    public S3ConnectionCreate(
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

    public S3ConnectionCreate withoutNamespace() {
        this.withoutNamespace = true;
        return this;
    }

    @Override
    protected S3Connection create(int index) {
        var namespace = getNamespace();
        var name = getName();

        var item = new S3Connection();

        item.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .build()
        );

        var spec = new S3ConnectionSpec();

        spec.setAdminEndpoint(getAdminEndpoint());
        spec.setAdminSecretRef(getAdminSecretRef());

        item.setSpec(spec);

        kubernetesClient.resources(S3Connection.class)
                .inNamespace(namespace)
                .resource(item)
                .serverSideApply();

        //noinspection ConstantConditions
        return kubernetesClient.resources(S3Connection.class)
                .inNamespace(namespace)
                .withName(name)
                .waitUntilCondition(
                        s3Connection -> s3Connection != null && s3Connection.getStatus() != null,
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

        withName = randomKubernetesNameSuffix("test-s3-connection");

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
