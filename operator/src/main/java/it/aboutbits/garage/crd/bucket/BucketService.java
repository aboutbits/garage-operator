package it.aboutbits.garage.crd.bucket;

import it.aboutbits.garage.core.Quantities;
import it.aboutbits.garage.core.adminapi.BucketQuotas;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

@Singleton
@NullMarked
public class BucketService {
    /// An absent section means no limits, so removing `quotas` lifts the limits on the backend
    /// rather than keeping them.
    public BucketQuotas desiredQuotas(@Nullable BucketQuotasSpec quotasSpec) {
        if (quotasSpec == null) {
            return BucketQuotas.NONE;
        }

        var maxSize = quotasSpec.getMaxSize();

        return new BucketQuotas(
                maxSize == null
                        ? null
                        : Quantities.toBytes(maxSize, "quotas maxSize"),
                quotasSpec.getMaxObjects()
        );
    }

    public boolean quotasMatch(
            BucketQuotas currentQuotas,
            BucketQuotas desiredQuotas
    ) {
        return Objects.equals(currentQuotas.maxSizeBytes(), desiredQuotas.maxSizeBytes())
                && Objects.equals(currentQuotas.maxObjects(), desiredQuotas.maxObjects());
    }
}
