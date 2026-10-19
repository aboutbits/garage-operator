package it.aboutbits.garage.crd.garageclusterconnection;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import it.aboutbits.garage.core.SecretKeyRef;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;

@Getter
@Setter
@NullMarked
public class GarageClusterConnectionSpec {
    /// The Garage Admin API on port 3903, not the S3 endpoint, e.g. `http://acme-garage.acme.svc:3903`.
    @Required
    @ValidationRule(
            value = "isURL(self) && url(self).getScheme() in ['http', 'https'] && url(self).getHostname() != ''",
            message = "The admin endpoint must be an http:// or https:// URL with a host."
    )
    // Buckets and access keys record the ids this Garage gave them, so another Garage would get second ones.
    @ValidationRule(
            value = "self == oldSelf",
            message = "The adminEndpoint is immutable. Delete and recreate the GarageClusterConnection to point it at a different Garage."
    )
    private String adminEndpoint = "";

    /// The Garage Helm chart's `Opaque` Secret `<release>-garage`, with the key `admin_token`.
    @Required
    private SecretKeyRef adminSecretRef = new SecretKeyRef();
}
