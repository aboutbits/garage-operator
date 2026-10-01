package it.aboutbits.garage._support.testdata.persisted;

import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.persisted.creator.GarageClusterCreate;
import it.aboutbits.garage._support.testdata.persisted.creator.S3ConnectionCreate;
import it.aboutbits.garage._support.testdata.persisted.creator.SecretKeyRefCreate;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NullMarked;

@ApplicationScoped
@RequiredArgsConstructor
@NullMarked
public class Given {
    private final KubernetesClient kubernetesClient;

    /// Host port the Compose Dev Service published the Garage Admin API on.
    @SuppressWarnings("NullAway.Init")
    @ConfigProperty(name = "garage.admin.port")
    Integer garageAdminPort;

    @SuppressWarnings("NullAway.Init")
    @ConfigProperty(name = "garage.admin.token")
    String garageAdminToken;

    public GarageConnectionDetails garageConnectionDetails() {
        return new GarageConnectionDetails(
                "http://localhost:%d".formatted(garageAdminPort),
                garageAdminToken
        );
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
