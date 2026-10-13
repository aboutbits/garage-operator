package it.aboutbits.garage.core;

import io.fabric8.crdv2.generator.v1.SchemaCustomizer;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import it.aboutbits.garage.core.schema_customizer.KubernetesNameCustomizer;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A reference to a Kubernetes resource identified by [#namespace] (optional) and [#name].
///
/// This class is used wherever a CRD spec needs to point to another Kubernetes resource.
///
/// ### Namespace resolution
///
/// The [#namespace] field is **nullable**. When it is `null` (or omitted
/// in the CR manifest), the operator resolves the target resource in the
/// **same namespace as the CR that contains the reference**. This convention
/// keeps single-namespace deployments simple — users only need to set
/// `namespace` when referring to a resource in a *different* namespace.
///
/// | `namespace` value | Resolved namespace                                  |
/// |-------------------|-----------------------------------------------------|
/// | non-null          | the explicit namespace                              |
/// | `null` (omitted)  | the namespace of the CR that owns this reference    |
@Getter
@Setter
// Scoped to `namespace` rather than left blank (which would mean "every string property"): a
// namespace is a DNS label, but most object names, such as the name of a Secret, are DNS
// subdomains. The customizer is also inherited by subclasses such as SecretKeyRef, whose
// additional fields follow different naming rules.
@SchemaCustomizer(value = KubernetesNameCustomizer.class, input = "namespace")
@NullMarked
public class ResourceRef {
    /// RFC 1123 DNS subdomain, see https://kubernetes.io/docs/concepts/overview/working-with-objects/names/
    private static final String DNS_SUBDOMAIN_PATTERN =
            "^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$";

    /// The namespace of the referenced Kubernetes resource.
    /// If `null`, defaults to the namespace of the CR that defines this reference.
    @io.fabric8.generator.annotation.Nullable
    private @Nullable String namespace;

    @Required
    @Size(max = 253)
    @Pattern(DNS_SUBDOMAIN_PATTERN)
    @ValidationRule(
            value = "self.trim().size() > 0",
            message = "The name must not be empty."
    )
    private String name = "";
}
