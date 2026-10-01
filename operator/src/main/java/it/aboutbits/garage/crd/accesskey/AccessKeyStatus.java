package it.aboutbits.garage.crd.accesskey;

import it.aboutbits.garage.core.CRStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The secret access key is deliberately absent; it lives only in the Secret.
@Getter
@Setter
@Accessors(chain = true)
@NullMarked
public class AccessKeyStatus extends CRStatus {
    private @Nullable String accessKeyId = null;

    private @Nullable String secretName = null;
}
