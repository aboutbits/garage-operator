package it.aboutbits.garage._support.testdata.base;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage.crd.accesskey.AccessKey;
import it.aboutbits.garage.crd.bucket.Bucket;
import it.aboutbits.garage.crd.bucketaccess.BucketAccess;
import it.aboutbits.garage.crd.garagecluster.GarageCluster;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import org.jspecify.annotations.NullMarked;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

@NullMarked
public final class TestUtil {
    public static void resetEnvironment(KubernetesClient kubernetesClient) {
        // Reverse Dependency Deletion
        deleteResource(kubernetesClient, BucketAccess.class);
        deleteResource(kubernetesClient, AccessKey.class);
        deleteResource(kubernetesClient, Bucket.class);
        deleteResource(kubernetesClient, S3Connection.class);
        deleteResource(kubernetesClient, GarageCluster.class);
    }

    public static void deleteResource(
            KubernetesClient kubernetesClient,
            Class<? extends HasMetadata> resourceClass
    ) {
        // Async call to the Kubernetes API Server that triggers the Operator Controllers
        kubernetesClient.resources(resourceClass).delete();

        // Wait for the Operator Controller to finish the CR cleanup
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> kubernetesClient.resources(resourceClass).list().getItems().isEmpty());
    }

    private TestUtil() {
    }
}
