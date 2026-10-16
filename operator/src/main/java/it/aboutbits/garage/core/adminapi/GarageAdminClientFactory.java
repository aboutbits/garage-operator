package it.aboutbits.garage.core.adminapi;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import it.aboutbits.garage.core.KubernetesService;
import it.aboutbits.garage.core.SecretKeyRef;
import it.aboutbits.garage.crd.clusterconnection.ClusterConnection;
import it.aboutbits.garage.crd.garagecluster.GarageCluster;
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

/// Clients are kept per admin endpoint and token, because each holds a connection pool. The token is part of the key,
/// because each client sends one fixed token through its [BearerTokenFilter]. The token is read from its Secret at
/// every reconcile, so a new token gets a new client at the next reconcile.
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

    public GarageAdminApi create(ClusterConnection clusterConnection) {
        var spec = clusterConnection.getSpec();

        return create(
                spec.getAdminEndpoint(),
                spec.getAdminSecretRef(),
                clusterConnection.getMetadata().getNamespace()
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

        var endpoint = parseEndpoint(adminEndpoint, adminSecretRef, defaultNamespace);

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
                .register(new GarageErrorMapper())
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
            SecretKeyRef adminSecretRef,
            String defaultNamespace
    ) {
        try {
            return new URI(adminEndpoint);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "The admin endpoint is not a valid URI [spec.adminEndpoint=%s, spec.adminSecretRef=%s/%s]".formatted(
                            adminEndpoint,
                            adminSecretRef.getNamespace() != null
                                    ? adminSecretRef.getNamespace()
                                    : defaultNamespace,
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
