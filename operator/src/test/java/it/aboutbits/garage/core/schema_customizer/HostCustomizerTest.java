package it.aboutbits.garage.core.schema_customizer;

import io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaProps;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class HostCustomizerTest {
    @Nested
    class Apply {
        @Test
        @DisplayName("A blank input should apply the host formats to all string properties")
        void blankInput_appliesToAllStringProperties() {
            // given
            var host = property("string");
            var anotherHost = property("string");

            // when
            new HostCustomizer().apply(
                    schema(Map.of(
                            "host", host,
                            "anotherHost", anotherHost
                    )),
                    "",
                    new KubernetesSerialization()
            );

            // then
            assertHostFormats(host);
            assertHostFormats(anotherHost);
        }

        @Test
        @DisplayName("A field list should apply the host formats only to the listed string properties")
        void fieldList_appliesOnlyToListedStringProperties() {
            // given
            var host = property("string");
            var anotherHost = property("string");
            var path = property("string");

            // when
            new HostCustomizer().apply(
                    schema(Map.of(
                            "host", host,
                            "anotherHost", anotherHost,
                            "path", path
                    )),
                    "host, anotherHost",
                    new KubernetesSerialization()
            );

            // then
            assertHostFormats(host);
            assertHostFormats(anotherHost);
            assertThat(path.getAnyOf()).isEmpty();
        }

        @Test
        @DisplayName("A property that is not a string should stay untouched, also when it is listed")
        void nonStringProperty_staysUntouched() {
            // given
            var host = property("string");
            var port = property("integer");

            // when
            new HostCustomizer().apply(
                    schema(Map.of(
                            "host", host,
                            "port", port
                    )),
                    "host,port",
                    new KubernetesSerialization()
            );

            // then
            assertHostFormats(host);
            assertThat(port.getAnyOf()).isEmpty();
        }

        @Test
        @DisplayName("A schema without properties should be returned unchanged")
        void noProperties_returnsSchemaUnchanged() {
            // given
            var schema = new JSONSchemaProps();

            // when
            var result = new HostCustomizer().apply(
                    schema,
                    "",
                    new KubernetesSerialization()
            );

            // then
            assertThat(result).isSameAs(schema).isEqualTo(new JSONSchemaProps());
        }
    }

    private static void assertHostFormats(JSONSchemaProps property) {
        assertThat(property.getFormat()).isNull();
        assertThat(property.getAnyOf())
                .extracting(JSONSchemaProps::getFormat)
                .containsExactly(
                        "hostname",
                        "ipv4",
                        "ipv6"
                );
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
