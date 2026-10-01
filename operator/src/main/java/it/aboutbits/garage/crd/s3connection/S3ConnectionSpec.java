package it.aboutbits.garage.crd.s3connection;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import it.aboutbits.garage.core.SecretKeyRef;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;

@Getter
@Setter
@NullMarked
public class S3ConnectionSpec {
    /// The Garage Admin API on port 3903, not the S3 endpoint, e.g. `http://acme-garage.acme.svc:3903`.
    @Required
    @ValidationRule(
            value = "self.startsWith('http://') || self.startsWith('https://')",
            message = "The admin endpoint must be an http:// or https:// URL."
    )
    private String adminEndpoint = "";

    /// The Garage Helm chart's `Opaque` Secret `<release>-garage`, with the key `admin_token`.
    @Required
    private SecretKeyRef adminSecretRef = new SecretKeyRef();
}
