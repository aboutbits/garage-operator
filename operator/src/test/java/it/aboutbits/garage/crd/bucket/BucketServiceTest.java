package it.aboutbits.garage.crd.bucket;

import it.aboutbits.garage.core.adminapi.BucketQuotas;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
            // when
            var quotas = bucketService.desiredQuotas(null);

            // then
            assertThat(quotas).isEqualTo(BucketQuotas.NONE);
            assertThat(quotas.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("A size quota should be converted to bytes")
        void maxSize_isConvertedToBytes() {
            // when
            var quotas = bucketService.desiredQuotas(quotasSpec("10Gi", null));

            // then
            assertThat(quotas.maxSizeBytes()).isEqualTo(10737418240L);
            assertThat(quotas.maxObjects()).isNull();
        }

        @Test
        @DisplayName("An object count quota should be carried over as-is")
        void maxObjects_isCarriedOver() {
            // when
            var quotas = bucketService.desiredQuotas(quotasSpec(null, 1000L));

            // then
            assertThat(quotas.maxSizeBytes()).isNull();
            assertThat(quotas.maxObjects()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("An unusable size quota should be rejected, naming the field")
        void invalidMaxSize_isRejected() {
            // given
            var quotasSpec = quotasSpec("ten gigabytes", null);

            // when
            var thrown = catchThrowable(() -> bucketService.desiredQuotas(quotasSpec));

            // then
            assertThat(thrown)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("spec.quotas.maxSize");
        }
    }
}
