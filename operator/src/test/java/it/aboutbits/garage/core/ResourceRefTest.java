package it.aboutbits.garage.core;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static it.aboutbits.garage.core.ResourceRef.DNS_SUBDOMAIN_PATTERN;
import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class ResourceRefTest {
    @Nested
    class NamePattern {
        @ParameterizedTest
        @ValueSource(strings = {"a", "my-name", "1password", "123", "example.com-tls", "a.b.c"})
        @DisplayName("A valid DNS subdomain should match the pattern")
        void validName_matches(String value) {
            // when
            var matches = value.matches(DNS_SUBDOMAIN_PATTERN);

            // then
            assertThat(matches).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "-a", "a-", "A", "a_b", ".a", "a.", "a..b", "a.-b"})
        @DisplayName("An invalid DNS subdomain should not match the pattern")
        void invalidName_doesNotMatch(String value) {
            // when
            var matches = value.matches(DNS_SUBDOMAIN_PATTERN);

            // then
            assertThat(matches).isFalse();
        }
    }
}
