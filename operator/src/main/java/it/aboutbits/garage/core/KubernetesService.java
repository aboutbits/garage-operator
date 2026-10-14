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
            var availableKeys = data.keySet();

            throw new IllegalStateException("The Secret reference is missing the referenced key [secret=%s/%s, secret.key=%s, availableKeys=%s]".formatted(
                    secretNamespace,
                    secretName,
                    secretKey,
                    availableKeys
            ));
        }

        // A token written with `echo "$TOKEN" > file` ends with a newline, which is invalid in an HTTP header.
        var token = decode(tokenBase64).strip();
        if (token.isBlank()) {
            throw new IllegalStateException("The Secret reference has a blank token [secret=%s/%s, secret.key=%s]".formatted(
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
            throw new IllegalStateException("Secret reference not found [secret=%s/%s]".formatted(
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
        var actualType = secret.getType();

        if (!expectedType.equals(actualType)) {
            throw new IllegalArgumentException("The Secret reference is of the wrong type [secret=%s/%s, expectedType=%s, actualType=%s]".formatted(
                    secretNamespace,
                    secretName,
                    expectedType,
                    actualType
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
            throw new IllegalStateException("The Secret reference has no data set [secret=%s/%s]".formatted(
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
