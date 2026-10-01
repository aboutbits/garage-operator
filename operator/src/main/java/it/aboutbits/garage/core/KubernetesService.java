package it.aboutbits.garage.core;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NullMarked;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

@Singleton
@NullMarked
public final class KubernetesService {
    public static final String SECRET_TYPE_OPAQUE = "Opaque";

    /// The key the AboutBits Garage Helm chart stores the Admin API token under (https://github.com/aboutbits/helm-garage).
    public static final String SECRET_DATA_ADMIN_TOKEN_KEY = "admin_token";

    public static final String LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String LABEL_MANAGED_BY_VALUE = "garage-operator";

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

    /// Always written into the owner's namespace: cross-namespace owner references are ignored,
    /// leaving orphans with live credentials. A Secret the owner does not own is never taken over,
    /// as it would be garbage-collected with the owner.
    ///
    /// @return whether anything actually changed
    public boolean upsertOwnedSecret(
            KubernetesClient kubernetesClient,
            HasMetadata owner,
            String secretName,
            Map<String, String> data
    ) {
        var namespace = owner.getMetadata().getNamespace();

        var existing = kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(secretName)
                .get();

        if (existing != null && !isOwnedBy(existing, owner)) {
            throw new IllegalStateException("A Secret of this name already exists and is not owned by this resource, so it is left untouched. Choose a different secretName, or delete the Secret [secret.namespace=%s, secret.name=%s]".formatted(
                    namespace,
                    secretName
            ));
        }

        if (existing != null && isUpToDate(existing, data)) {
            return false;
        }

        var secret = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(namespace)
                .withName(secretName)
                .addToLabels(LABEL_MANAGED_BY, LABEL_MANAGED_BY_VALUE)
                .withOwnerReferences(new OwnerReferenceBuilder()
                        .withApiVersion(owner.getApiVersion())
                        .withKind(owner.getKind())
                        .withName(owner.getMetadata().getName())
                        .withUid(owner.getMetadata().getUid())
                        .withController(true)
                        .withBlockOwnerDeletion(false)
                        .build()
                )
                .endMetadata()
                .withType(SECRET_TYPE_OPAQUE)
                .withStringData(data)
                .build();

        // Forced, as the Secret is verified to be the owner's own: hand-edited fields would otherwise conflict.
        kubernetesClient.secrets()
                .inNamespace(namespace)
                .resource(secret)
                .forceConflicts()
                .serverSideApply();

        return true;
    }

    private boolean isUpToDate(
            Secret secret,
            Map<String, String> data
    ) {
        var currentData = secret.getData();

        if (currentData == null || currentData.size() != data.size()) {
            return false;
        }

        return data.entrySet().stream().allMatch(entry -> {
            var encoded = currentData.get(entry.getKey());

            return encoded != null && entry.getValue().equals(decode(encoded));
        });
    }

    private boolean isOwnedBy(
            Secret secret,
            HasMetadata owner
    ) {
        var ownerReferences = secret.getMetadata().getOwnerReferences();

        return ownerReferences != null
                && ownerReferences.stream().anyMatch(
                        ownerReference -> Objects.equals(ownerReference.getUid(), owner.getMetadata().getUid())
                );
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
