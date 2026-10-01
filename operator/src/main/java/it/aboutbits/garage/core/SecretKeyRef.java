package it.aboutbits.garage.core;

import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;

/// [#key] is deliberately not validated as an object name: Secret data keys are more permissive (`admin_token`).
@Getter
@Setter
@NullMarked
public class SecretKeyRef extends ResourceRef {
    @Required
    @Max(253)
    @ValidationRule(
            value = "self.trim().size() > 0",
            message = "The Secret key must not be empty."
    )
    private String key = "";
}
