package it.aboutbits.garage.crd.garagecluster;

import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import io.fabric8.kubernetes.api.model.IntOrString;
import it.aboutbits.garage.core.SecretKeyRef;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NullMarked
public class GarageClusterSpec {
    /// The Garage Admin API (port 3903), not the S3 endpoint, e.g. `http://my-release-garage.storage.svc:3903`.
    @Required
    @ValidationRule(
            value = "isURL(self) && url(self).getScheme() in ['http', 'https'] && url(self).getHostname() != ''",
            message = "The admin endpoint must be an http:// or https:// URL with a host."
    )
    private String adminEndpoint = "";

    /// An `Opaque` Secret with the Admin API token. The key has to be spelled out: the CRD schema carries no defaults.
    @Required
    private SecretKeyRef adminSecretRef = new SecretKeyRef();

    /// The role every storage node gets, not the whole layout: Garage keeps one layout per cluster with a role per node.
    @Required
    private GarageClusterLayoutSpec layout = new GarageClusterLayoutSpec();

    /// The layout is left alone while fewer nodes are connected, so that the first layout covers all of them at once.
    /// Not set means 1.
    @io.fabric8.generator.annotation.Nullable
    @Min(1)
    private @Nullable Integer minNodes = null;

    /// Not set leaves the parameter as it is in Garage, which starts with `maximum`.
    @io.fabric8.generator.annotation.Nullable
    @ValidationRule(
            value = "type(self) == int ? self >= 1 : self == 'maximum'",
            message = "The zoneRedundancy must be 'maximum' or a number of zones of at least 1."
    )
    private @Nullable IntOrString zoneRedundancy = null;

    /// Node ids rather than hostnames: a node that replaces a dead one under a new id may reuse its hostname.
    /// A pattern rather than a validation rule: Kubernetes refuses a rule over a list of unbounded strings as too costly.
    @io.fabric8.generator.annotation.Nullable
    private @Nullable List<@Pattern("^[0-9a-f]{64}$") String> removedNodes = new ArrayList<>();

    public List<String> getRemovedNodes() {
        return removedNodes != null
                ? removedNodes
                : List.of();
    }
}
