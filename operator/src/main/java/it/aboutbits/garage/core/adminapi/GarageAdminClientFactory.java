package it.aboutbits.garage.core.adminapi;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import it.aboutbits.garage.core.KubernetesService;
import it.aboutbits.garage.core.SecretKeyRef;
import it.aboutbits.garage.crd.garagecluster.GarageCluster;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/// Clients are kept per admin endpoint and token, because each holds a connection pool. Keying by
/// token too keeps two resources with different tokens for one endpoint from closing each other's
/// client mid-request. The token is read from its Secret on every call, so rotation applies at once.
@ApplicationScoped
@RequiredArgsConstructor
@Slf4j
@NullMarked
public class GarageAdminClientFactory {
    private static final long CONNECT_TIMEOUT_SECONDS = 5;
    private static final long READ_TIMEOUT_SECONDS = 30;

    private final KubernetesService kubernetesService;
    private final KubernetesClient kubernetesClient;

    private final Map<ClientKey, GarageAdminApi> clients = new ConcurrentHashMap<>();

    public GarageAdminApi create(GarageCluster garageCluster) {
        var spec = garageCluster.getSpec();

        return create(
                spec.getAdminEndpoint(),
                spec.getAdminSecretRef(),
                garageCluster.getMetadata().getNamespace()
        );
    }

    public GarageAdminApi create(S3Connection s3Connection) {
        var spec = s3Connection.getSpec();

        return create(
                spec.getAdminEndpoint(),
                spec.getAdminSecretRef(),
                s3Connection.getMetadata().getNamespace()
        );
    }

    public GarageAdminApi create(
            String adminEndpoint,
            SecretKeyRef adminSecretRef,
            String defaultNamespace
    ) {
        var token = kubernetesService.getSecretRefToken(
                kubernetesClient,
                adminSecretRef,
                defaultNamespace
        );

        var endpoint = parseEndpoint(adminEndpoint, adminSecretRef);

        return clients.computeIfAbsent(
                new ClientKey(endpoint, token),
                key -> create(key.endpoint(), key.token())
        );
    }

    @PreDestroy
    void closeAll() {
        clients.values().forEach(this::close);
        clients.clear();
    }

    /// Uncached; the caller owns the client and has to close it.
    public GarageAdminApi create(
            URI adminEndpoint,
            String token
    ) {
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(adminEndpoint)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .register(new BearerTokenFilter(token))
                .build(GarageAdminApi.class);
    }

    private void close(GarageAdminApi garageAdminApi) {
        if (garageAdminApi instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to close a Garage Admin API client", e);
            }
        }
    }

    private URI parseEndpoint(
            String adminEndpoint,
            SecretKeyRef adminSecretRef
    ) {
        try {
            return new URI(adminEndpoint);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "The admin endpoint is not a valid URI [adminEndpoint=%s, adminSecretRef.name=%s]".formatted(
                            adminEndpoint,
                            adminSecretRef.getName()
                    ),
                    e
            );
        }
    }

    private record ClientKey(
            URI endpoint,
            String token
    ) {
        // Hides the token from logs and exception messages.
        @Override
        public String toString() {
            return "ClientKey[endpoint=%s]".formatted(endpoint);
        }
    }
}
