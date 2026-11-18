package it.aboutbits.garage.core;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@NullMarked
@EnableKubernetesMockClient(crud = true)
class KubernetesServiceTest {
    private static final String NAMESPACE = "acme";
    private static final String DEFAULT_NAMESPACE = "default-ns";
    private static final String SECRET_NAME = "acme-garage-admin";
    private static final String SECRET_KEY = "GARAGE_ADMIN_TOKEN";

    @SuppressWarnings("NullAway.Init")
    static KubernetesClient client;

    private final KubernetesService kubernetesService = new KubernetesService();

    @BeforeEach
    void clearSecrets() {
        client.secrets().inAnyNamespace().delete();
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static SecretKeyRef secretKeyRef(@Nullable String namespace) {
        var secretKeyRef = new SecretKeyRef();

        secretKeyRef.setNamespace(namespace);
        secretKeyRef.setName(SECRET_NAME);
        secretKeyRef.setKey(SECRET_KEY);

        return secretKeyRef;
    }

    private static void givenSecret(
            String namespace,
            String type,
            @Nullable Map<String, String> data
    ) {
        var secret = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(namespace)
                .withName(SECRET_NAME)
                .endMetadata()
                .withType(type)
                .withData(data)
                .build();

        client.secrets()
                .inNamespace(namespace)
                .resource(secret)
                .create();
    }

    @Nested
    class GetSecretRefToken {
        @Test
        @DisplayName("The token of an Opaque Secret should be returned")
        void opaqueSecret_returnsToken() {
            // given
            givenSecret(NAMESPACE, KubernetesService.SECRET_TYPE_OPAQUE, Map.of(SECRET_KEY, base64("s3cret")));

            // when
            var token = kubernetesService.getSecretRefToken(client, secretKeyRef(NAMESPACE), DEFAULT_NAMESPACE);

            // then
            assertThat(token).isEqualTo("s3cret");
        }

        @Test
        @DisplayName("A newline at the end of the token should be stripped")
        void trailingNewline_isStripped() {
            // given
            givenSecret(NAMESPACE, KubernetesService.SECRET_TYPE_OPAQUE, Map.of(SECRET_KEY, base64("s3cret\n")));

            // when
            var token = kubernetesService.getSecretRefToken(client, secretKeyRef(NAMESPACE), DEFAULT_NAMESPACE);

            // then
            assertThat(token).isEqualTo("s3cret");
        }

        @Test
        @DisplayName("A reference without a namespace should look in the default namespace")
        void noNamespace_usesDefaultNamespace() {
            // given
            givenSecret(DEFAULT_NAMESPACE, KubernetesService.SECRET_TYPE_OPAQUE, Map.of(SECRET_KEY, base64("s3cret")));

            // when
            var token = kubernetesService.getSecretRefToken(client, secretKeyRef(null), DEFAULT_NAMESPACE);

            // then
            assertThat(token).isEqualTo("s3cret");
        }

        @Test
        @DisplayName("A missing Secret should be refused")
        void missingSecret_isRefused() {
            // when
            var thrown = catchThrowable(
                    () -> kubernetesService.getSecretRefToken(client, secretKeyRef(NAMESPACE), DEFAULT_NAMESPACE)
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Secret reference not found [secret=acme/acme-garage-admin]");
        }

        @Test
        @DisplayName("A Secret of another type should be refused")
        void wrongType_isRefused() {
            // given
            givenSecret(NAMESPACE, "kubernetes.io/basic-auth", Map.of(SECRET_KEY, base64("s3cret")));

            // when
            var thrown = catchThrowable(
                    () -> kubernetesService.getSecretRefToken(client, secretKeyRef(NAMESPACE), DEFAULT_NAMESPACE)
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The Secret reference is of the wrong type [secret=acme/acme-garage-admin, expectedType=Opaque, actualType=kubernetes.io/basic-auth]");
        }

        @Test
        @DisplayName("A Secret without data should be refused")
        void noData_isRefused() {
            // given
            givenSecret(NAMESPACE, KubernetesService.SECRET_TYPE_OPAQUE, null);

            // when
            var thrown = catchThrowable(
                    () -> kubernetesService.getSecretRefToken(client, secretKeyRef(NAMESPACE), DEFAULT_NAMESPACE)
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The Secret reference has no data set [secret=acme/acme-garage-admin]");
        }

        @Test
        @DisplayName("A Secret with an empty data map should be refused")
        void emptyData_isRefused() {
            // given
            givenSecret(NAMESPACE, KubernetesService.SECRET_TYPE_OPAQUE, Map.of());

            // when
            var thrown = catchThrowable(
                    () -> kubernetesService.getSecretRefToken(client, secretKeyRef(NAMESPACE), DEFAULT_NAMESPACE)
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The Secret reference has no data set [secret=acme/acme-garage-admin]");
        }

        @Test
        @DisplayName("A Secret without the referenced key should be refused, and name the keys it has")
        void missingKey_isRefused() {
            // given
            givenSecret(NAMESPACE, KubernetesService.SECRET_TYPE_OPAQUE, Map.of("other", base64("value")));

            // when
            var thrown = catchThrowable(
                    () -> kubernetesService.getSecretRefToken(client, secretKeyRef(NAMESPACE), DEFAULT_NAMESPACE)
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The Secret reference is missing the referenced key [secret=acme/acme-garage-admin, secret.key=GARAGE_ADMIN_TOKEN, availableKeys=[other]]");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\n"})
        @DisplayName("A blank token should be refused")
        void blankToken_isRefused(String token) {
            // given
            givenSecret(NAMESPACE, KubernetesService.SECRET_TYPE_OPAQUE, Map.of(SECRET_KEY, base64(token)));

            // when
            var thrown = catchThrowable(
                    () -> kubernetesService.getSecretRefToken(client, secretKeyRef(NAMESPACE), DEFAULT_NAMESPACE)
            );

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The Secret reference has a blank token [secret=acme/acme-garage-admin, secret.key=GARAGE_ADMIN_TOKEN]");
        }
    }
}
