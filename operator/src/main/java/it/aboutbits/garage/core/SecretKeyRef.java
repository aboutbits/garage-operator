package it.aboutbits.garage.core;

import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;

/// [#key] is deliberately not validated as an object name: Secret data keys are more permissive (`GARAGE_ADMIN_TOKEN`).
@Getter
@Setter
@NullMarked
public class SecretKeyRef extends ResourceRef {
    @Required
    @Size(max = 253)
    @Pattern("^[-._a-zA-Z0-9]+$")
    private String key = "";
}
