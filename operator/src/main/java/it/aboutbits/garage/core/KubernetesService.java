package it.aboutbits.garage.core;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NullMarked;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Map;

@Singleton
@NullMarked
public final class KubernetesService {
    public static final String SECRET_TYPE_OPAQUE = "Opaque";

    /// The key the AboutBits Garage Helm chart stores the Admin API token under (https://github.com/aboutbits/helm-garage).
    public static final String SECRET_DATA_ADMIN_TOKEN_KEY = "admin_token";

    public String getSecretRefToken(
            KubernetesClient kubernetesClient,
            SecretKeyRef secretRef,
            String defaultNamespace
    ) {
        var secretNamespace = getSecretNamespaceOrDefault(secretRef, defaultNamespace);
        var secretName = secretRef.getName();
        var secretKey = secretRef.getKey();

        var secret = getSecret(
                kubernetesClient,
                secretNamespace,
                secretName
        );

        requireSecretType(
                secret,
                SECRET_TYPE_OPAQUE,
                secretNamespace,
                secretName
        );

        var data = requireSecretData(
                secret,
                secretNamespace,
                secretName
        );

        var tokenBase64 = data.get(secretKey);
        if (tokenBase64 == null) {
            throw new IllegalStateException("The Secret reference is missing the referenced key [secret.namespace=%s, secret.name=%s, secret.data.key=%s, available.secret.data.keys=%s]".formatted(
                    secretNamespace,
                    secretName,
                    secretKey,
                    data.keySet()
            ));
        }

        var token = decode(tokenBase64);
        if (token.isBlank()) {
            throw new IllegalStateException("The Secret reference has a blank token [secret.namespace=%s, secret.name=%s, secret.data.key=%s]".formatted(
                    secretNamespace,
                    secretName,
                    secretKey
            ));
        }

        return token;
    }

    private String getSecretNamespaceOrDefault(
            ResourceRef secretRef,
            String defaultNamespace
    ) {
        return secretRef.getNamespace() != null
                ? secretRef.getNamespace()
                : defaultNamespace;
    }

    private Secret getSecret(
            KubernetesClient kubernetesClient,
            String secretNamespace,
            String secretName
    ) {
        var secret = kubernetesClient.secrets()
                .inNamespace(secretNamespace)
                .withName(secretName)
                .get();

        if (secret == null) {
            throw new IllegalStateException("Secret reference not found [secret.namespace=%s, secret.name=%s]".formatted(
                    secretNamespace,
                    secretName
            ));
        }

        return secret;
    }

    private void requireSecretType(
            Secret secret,
            String expectedType,
            String secretNamespace,
            String secretName
    ) {
        if (!expectedType.equals(secret.getType())) {
            throw new IllegalArgumentException("The Secret reference is of the wrong type [secret.namespace=%s, secret.name=%s, expected.secret.type=%s, actual.secret.type=%s]".formatted(
                    secretNamespace,
                    secretName,
                    expectedType,
                    secret.getType()
            ));
        }
    }

    private Map<String, String> requireSecretData(
            Secret secret,
            String secretNamespace,
            String secretName
    ) {
        var data = secret.getData();

        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("The Secret reference has no data set [secret.namespace=%s, secret.name=%s]".formatted(
                    secretNamespace,
                    secretName
            ));
        }

        return data;
    }

    private String decode(String base64Value) {
        return new String(
                Base64.getDecoder().decode(base64Value),
                Charset.defaultCharset()
        );
    }
}
