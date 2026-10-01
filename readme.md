# AboutBits Garage Operator

AboutBits Garage Operator is a Kubernetes operator that manages S3 object storage declaratively: the cluster layout of a [Garage](https://garagehq.deuxfleurs.fr/) installation, buckets, access keys and the permissions that tie them together, all as Custom Resources.

It is the companion of the [AboutBits Garage Helm chart](https://github.com/aboutbits/helm-garage): the chart runs Garage, the operator sets it up and manages what lives on it.

## Compatibility

| Component      | Supported Versions                        |
|----------------|-------------------------------------------|
| **Garage**     | 2.x (Admin API v2), tested against v2.3.0 |
| **Kubernetes** | 1.29+                                     |

> **Note:** Kubernetes 1.29+ is required due to the use of CRD CEL validations (GA in 1.29, Beta in 1.25).

## Contribute

These instructions will get you a copy of the project up and running on your local machine for development and testing purposes.

### Prerequisites

To build the project, the following prerequisites must be met:

- Java JDK 25 (provisioned automatically by the Gradle toolchain)
- [Docker](https://www.docker.com/), for the Quarkus Dev Services
- The [`helm`](https://helm.sh/) CLI, for the Helm chart test

### Setup

To get started, you first need to configure the GitHub Gradle Packages registry to be able to pull the
[AboutBits Java Checkstyle Config](https://github.com/aboutbits/java-checkstyle-config) from the GitHub Packages registry.

Follow <https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry>  
The guide basically tells you to click on `Generate new token (classic)` on <https://github.com/settings/tokens>, add the permission `read:packages` and copy the token which we need below.

If it does not exist yet, create a file `~/.gradle/gradle.properties` in your home directory and add the following lines.

```properties
gpr.user=<your_github_user>
# The token generated above
gpr.key=<your_github_token>
```

Alternatively, set the environment variables `GITHUB_USER_NAME` and `GITHUB_ACCESS_TOKEN`.

Then call:

```bash
make init

# or

./gradlew :operator:quarkusBuild
```

Enable the pre-commit hook with:

```bash
git config core.hooksPath .githooks
```

### Development

You can run the operator in dev mode, against a throwaway k3s cluster, using:

```bash
make run

# or

./gradlew :operator:quarkusDev
```

To execute the tests, run:

```bash
make test

# or

./gradlew :operator:test
```

The Helm chart installation test currently fails against **Helm 4**, whose server-side apply conflicts with the CRDs the fabric8 client already applied in test mode; CI runs Helm 3. The same is true of the sibling
[PostgreSQL Operator](https://github.com/aboutbits/postgresql-operator).

## Information

About Bits is a company based in South Tyrol, Italy. You can find more information about us on [our website](https://aboutbits.it).

### Support

For support, please contact [info@aboutbits.it](mailto:info@aboutbits.it).

### Credits

- [All Contributors](https://github.com/aboutbits/garage-operator/graphs/contributors)

### License

The MIT License (MIT). Please see the [license file](LICENSE) for more information.
