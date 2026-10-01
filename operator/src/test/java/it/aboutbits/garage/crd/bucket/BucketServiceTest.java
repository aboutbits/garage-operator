package it.aboutbits.garage.crd.bucket;

import it.aboutbits.garage.core.adminapi.BucketQuotas;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@NullMarked
class BucketServiceTest {
    private final BucketService bucketService = new BucketService();

    private static BucketQuotasSpec quotasSpec(
            @Nullable String maxSize,
            @Nullable Long maxObjects
    ) {
        var quotasSpec = new BucketQuotasSpec();

        quotasSpec.setMaxSize(maxSize);
        quotasSpec.setMaxObjects(maxObjects);

        return quotasSpec;
    }

    @Nested
    class DesiredQuotas {
        @Test
        @DisplayName("An absent quotas section should mean no limits")
        void absentQuotas_meansNoLimits() {
            var quotas = bucketService.desiredQuotas(null);

            assertThat(quotas).isEqualTo(BucketQuotas.NONE);
            assertThat(quotas.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("A size quota should be converted to bytes")
        void maxSize_isConvertedToBytes() {
            var quotas = bucketService.desiredQuotas(quotasSpec("10Gi", null));

            assertThat(quotas.maxSizeBytes()).isEqualTo(10737418240L);
            assertThat(quotas.maxObjects()).isNull();
        }

        @Test
        @DisplayName("An object count quota should be carried over as-is")
        void maxObjects_isCarriedOver() {
            var quotas = bucketService.desiredQuotas(quotasSpec(null, 1000L));

            assertThat(quotas.maxSizeBytes()).isNull();
            assertThat(quotas.maxObjects()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("An unusable size quota should be rejected, naming the field")
        void invalidMaxSize_isRejected() {
            var quotasSpec = quotasSpec("ten gigabytes", null);

            assertThatThrownBy(() -> bucketService.desiredQuotas(quotasSpec))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quotas maxSize");
        }
    }

    @Nested
    class QuotasMatch {
        @Test
        @DisplayName("Identical quotas should match")
        void identicalQuotas_match() {
            assertThat(bucketService.quotasMatch(
                    new BucketQuotas(10737418240L, 1000L),
                    new BucketQuotas(10737418240L, 1000L)
            )).isTrue();
        }

        @Test
        @DisplayName("Two unlimited buckets should match")
        void noQuotas_match() {
            assertThat(bucketService.quotasMatch(BucketQuotas.NONE, BucketQuotas.NONE)).isTrue();
        }

        @Test
        @DisplayName("A quota that is set on only one side should not match")
        void oneSidedQuota_doesNotMatch() {
            assertThat(bucketService.quotasMatch(
                    new BucketQuotas(10737418240L, null),
                    BucketQuotas.NONE
            )).isFalse();
        }

        @Test
        @DisplayName("A different object count should not match")
        void differentMaxObjects_doNotMatch() {
            assertThat(bucketService.quotasMatch(
                    new BucketQuotas(null, 1000L),
                    new BucketQuotas(null, 2000L)
            )).isFalse();
        }
    }
}
