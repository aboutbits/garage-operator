package it.aboutbits.garage._support.testdata.persisted.creator;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.aboutbits.garage._support.testdata.base.TestDataCreator;
import it.aboutbits.garage.core.SecretKeyRef;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static it.aboutbits.garage.core.KubernetesService.SECRET_DATA_ADMIN_TOKEN_KEY;
import static it.aboutbits.garage.core.KubernetesService.SECRET_TYPE_OPAQUE;

/// Creates an `Opaque` Secret holding a token, plus the [SecretKeyRef] pointing at it.
@Setter
@Accessors(fluent = true, chain = true)
@NullMarked
public class SecretKeyRefCreate extends TestDataCreator<SecretKeyRef> {
    private final KubernetesClient kubernetesClient;

    private @Nullable String withNamespace;

    @Setter(AccessLevel.NONE)
    private boolean withoutNamespace = false;

    private @Nullable String withName;

    private @Nullable String withKey;

    private @Nullable String withToken;

    @Setter(AccessLevel.NONE)
    private boolean withoutToken = false;

    @Setter(AccessLevel.NONE)
    private boolean withoutSecret = false;

    public SecretKeyRefCreate(
            int numberOfItems,
            KubernetesClient kubernetesClient
    ) {
        super(numberOfItems);
        this.kubernetesClient = kubernetesClient;
    }

    public SecretKeyRefCreate withoutNamespace() {
        this.withoutNamespace = true;
        return this;
    }

    public SecretKeyRefCreate withoutToken() {
        this.withoutToken = true;
        return this;
    }

    /// Return a reference that points at a Secret which is never created.
    public SecretKeyRefCreate withoutSecret() {
        this.withoutSecret = true;
        return this;
    }

    @Override
    protected SecretKeyRef create(int index) {
        var namespace = getNamespace();
        var name = getName();
        var key = getKey();

        if (!withoutSecret) {
            var secretBuilder = new SecretBuilder()
                    .withNewMetadata()
                    .withNamespace(namespace)
                    .withName(name)
                    .endMetadata()
                    .withType(SECRET_TYPE_OPAQUE);

            if (!withoutToken) {
                secretBuilder.addToStringData(key, getToken());
            } else {
                // A Secret with data, but not the referenced key.
                secretBuilder.addToStringData("unrelated", "value");
            }

            kubernetesClient.secrets()
                    .inNamespace(namespace)
                    .resource(secretBuilder.build())
                    .serverSideApply();
        }

        var secretKeyRef = new SecretKeyRef();

        secretKeyRef.setName(name);
        secretKeyRef.setNamespace(namespace);
        secretKeyRef.setKey(key);

        return secretKeyRef;
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

        withName = randomKubernetesNameSuffix("test-admin-secret");

        return withName;
    }

    private String getKey() {
        if (withKey != null) {
            return withKey;
        }

        withKey = SECRET_DATA_ADMIN_TOKEN_KEY;

        return withKey;
    }

    private String getToken() {
        if (withToken != null) {
            return withToken;
        }

        withToken = FAKER.regexify("[a-z0-9]{32}");

        return withToken;
    }
}
