package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import it.aboutbits.garage.core.SecretKeyRef;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;

@Getter
@Setter
@NullMarked
public class GarageClusterSpec {
    /// The Garage Admin API (port 3903), not the S3 endpoint, e.g. `http://my-release-garage.storage.svc:3903`.
    @Required
    @ValidationRule(
            value = "self.startsWith('http://') || self.startsWith('https://')",
            message = "The admin endpoint must be an http:// or https:// URL."
    )
    private String adminEndpoint = "";

    /// The chart's `Opaque` Secret, i.e. `{name: <release>-garage, key: admin_token}`.
    /// The key has to be spelled out: the CRD schema carries no defaults.
    @Required
    private SecretKeyRef adminSecretRef = new SecretKeyRef();

    @Required
    private GarageClusterLayoutSpec layout = new GarageClusterLayoutSpec();
}
