package it.aboutbits.garage._support.testdata.persisted;

import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.persisted.creator.AccessKeyCreate;
import it.aboutbits.garage._support.testdata.persisted.creator.BucketCreate;
import it.aboutbits.garage._support.testdata.persisted.creator.GarageClusterCreate;
import it.aboutbits.garage._support.testdata.persisted.creator.S3ConnectionCreate;
import it.aboutbits.garage._support.testdata.persisted.creator.SecretKeyRefCreate;
import it.aboutbits.garage.core.CRPhase;
import it.aboutbits.garage.core.ResourceRef;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NullMarked;

import static it.aboutbits.garage._support.testdata.base.TestUtil.awaitPhase;

@ApplicationScoped
@RequiredArgsConstructor
@NullMarked
public class Given {
    private final KubernetesClient kubernetesClient;

    /// Host port the Compose Dev Service published the Garage Admin API on.
    @ConfigProperty(name = "garage.admin.port")
    private final int garageAdminPort;

    @ConfigProperty(name = "garage.admin.token")
    private final String garageAdminToken;

    public GarageConnectionDetails garageConnectionDetails() {
        return new GarageConnectionDetails(
                "http://localhost:%d".formatted(garageAdminPort),
                garageAdminToken
        );
    }

    /// Apply a cluster layout, without which Garage serves no bucket or key calls at all.
    public void appliedLayout() {
        var garageCluster = one()
                .garageCluster()
                .returnFirst();

        awaitPhase(kubernetesClient, garageCluster, CRPhase.READY);
    }

    /// Bring the cluster up and return a ready connection in the default namespace.
    public ResourceRef readyConnection() {
        return readyConnection(kubernetesClient.getNamespace());
    }

    /// Bring the cluster up and return a ready connection in the given namespace.
    public ResourceRef readyConnection(String namespace) {
        appliedLayout();

        var s3Connection = one()
                .s3Connection()
                .withNamespace(namespace)
                .returnFirst();

        awaitPhase(kubernetesClient, s3Connection, CRPhase.READY);

        var connectionRef = new ResourceRef();

        connectionRef.setNamespace(s3Connection.getMetadata().getNamespace());
        connectionRef.setName(s3Connection.getMetadata().getName());

        return connectionRef;
    }

    public One one() {
        return new One(this);
    }

    public Many many(int numberOfItems) {
        return new Many(numberOfItems, this);
    }

    public class One extends Item {
        One(Given given) {
            super(1, given);
        }
    }

    public class Many extends Item {
        Many(int numberOfItems, Given given) {
            super(numberOfItems, given);
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public abstract class Item {
        private final int numberOfItems;
        private final Given given;

        @SuppressWarnings("unused")
        public Item describedAs(String description) {
            return this;
        }

        public SecretKeyRefCreate secretKeyRef() {
            return new SecretKeyRefCreate(
                    numberOfItems,
                    kubernetesClient
            );
        }

        public GarageClusterCreate garageCluster() {
            return new GarageClusterCreate(
                    numberOfItems,
                    given,
                    kubernetesClient,
                    garageConnectionDetails()
            );
        }

        public AccessKeyCreate accessKey() {
            return new AccessKeyCreate(
                    numberOfItems,
                    given,
                    kubernetesClient
            );
        }

        public BucketCreate bucket() {
            return new BucketCreate(
                    numberOfItems,
                    given,
                    kubernetesClient
            );
        }

        public S3ConnectionCreate s3Connection() {
            return new S3ConnectionCreate(
                    numberOfItems,
                    given,
                    kubernetesClient,
                    garageConnectionDetails()
            );
        }
    }

    public record GarageConnectionDetails(
            String adminEndpoint,
            String adminToken
    ) {
    }
}
