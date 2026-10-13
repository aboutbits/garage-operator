package it.aboutbits.garage.core.schema_customizer;

import io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaProps;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static it.aboutbits.garage.core.schema_customizer.KubernetesNameCustomizer.KUBERNETES_NAME_PATTERN;
import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class KubernetesNameCustomizerTest {
    @Nested
    class Apply {
        @Test
        @DisplayName("A blank input should apply the pattern to all string properties")
        void blankInput_appliesToAllStringProperties() {
            // given
            var namespace = property("string");
            var name = property("string");

            // when
            new KubernetesNameCustomizer().apply(
                    schema(Map.of(
                            "namespace", namespace,
                            "name", name
                    )),
                    "",
                    new KubernetesSerialization()
            );

            // then
            assertThat(namespace.getPattern()).isEqualTo(KUBERNETES_NAME_PATTERN);
            assertThat(name.getPattern()).isEqualTo(KUBERNETES_NAME_PATTERN);
        }

        @Test
        @DisplayName("A field list should apply the pattern only to the listed string properties")
        void fieldList_appliesOnlyToListedStringProperties() {
            // given
            var namespace = property("string");
            var name = property("string");
            var key = property("string");

            // when
            new KubernetesNameCustomizer().apply(
                    schema(Map.of(
                            "namespace", namespace,
                            "name", name,
                            "key", key
                    )),
                    "namespace, name",
                    new KubernetesSerialization()
            );

            // then
            assertThat(namespace.getPattern()).isEqualTo(KUBERNETES_NAME_PATTERN);
            assertThat(name.getPattern()).isEqualTo(KUBERNETES_NAME_PATTERN);
            assertThat(key.getPattern()).isNull();
        }

        @Test
        @DisplayName("A property that is not a string should stay untouched, also when it is listed")
        void nonStringProperty_staysUntouched() {
            // given
            var namespace = property("string");
            var replicas = property("integer");

            // when
            new KubernetesNameCustomizer().apply(
                    schema(Map.of(
                            "namespace", namespace,
                            "replicas", replicas
                    )),
                    "namespace,replicas",
                    new KubernetesSerialization()
            );

            // then
            assertThat(namespace.getPattern()).isEqualTo(KUBERNETES_NAME_PATTERN);
            assertThat(replicas.getPattern()).isNull();
        }

        @Test
        @DisplayName("A schema without properties should be returned unchanged")
        void noProperties_returnsSchemaUnchanged() {
            // given
            var schema = new JSONSchemaProps();

            // when
            var result = new KubernetesNameCustomizer().apply(
                    schema,
                    "",
                    new KubernetesSerialization()
            );

            // then
            assertThat(result).isSameAs(schema).isEqualTo(new JSONSchemaProps());
        }
    }

    @Nested
    class NamePattern {
        @ParameterizedTest
        @ValueSource(strings = {"a", "my-name", "1password", "123"})
        @DisplayName("A valid DNS label should match the pattern")
        void validName_matches(String value) {
            // when
            var matches = value.matches(KUBERNETES_NAME_PATTERN);

            // then
            assertThat(matches).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "-a", "a-", "A", "a_b", "a.b"})
        @DisplayName("An invalid DNS label should not match the pattern")
        void invalidName_doesNotMatch(String value) {
            // when
            var matches = value.matches(KUBERNETES_NAME_PATTERN);

            // then
            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("A DNS label should be at most 63 characters long")
        void nameLength_isLimitedTo63() {
            // when
            var longestMatches = "a".repeat(63).matches(KUBERNETES_NAME_PATTERN);
            var tooLongMatches = "a".repeat(64).matches(KUBERNETES_NAME_PATTERN);

            // then
            assertThat(longestMatches).isTrue();
            assertThat(tooLongMatches).isFalse();
        }
    }

    private static JSONSchemaProps property(String type) {
        var property = new JSONSchemaProps();
        property.setType(type);
        return property;
    }

    private static JSONSchemaProps schema(Map<String, JSONSchemaProps> properties) {
        var schema = new JSONSchemaProps();
        schema.setType("object");
        schema.setProperties(properties);
        return schema;
    }
}
