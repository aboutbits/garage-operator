# AboutBits Garage Operator

AboutBits Garage Operator is a Kubernetes operator that manages S3 object storage declaratively: the cluster layout of a [Garage](https://garagehq.deuxfleurs.fr/) installation, buckets, access keys and the permissions that tie them together, all as Custom Resources.

It is the companion of the [AboutBits Garage Helm chart](https://github.com/aboutbits/helm-garage): the chart runs Garage, the operator sets it up and manages what lives on it.

## Compatibility

| Component      | Supported Versions                        |
|----------------|-------------------------------------------|
| **Garage**     | 2.x (Admin API v2), tested against v2.3.0 |
| **Kubernetes** | 1.29+                                     |

> **Note:** Kubernetes 1.29+ is required due to the use of CRD CEL validations (GA in 1.29, Beta in 1.25).

## Architecture

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                            Kubernetes Cluster                             │
│  ┌─────────────────────────┐   ┌────────────────────────────────────────┐ │
│  │ Garage Operator CRDs    │──▶│            Garage Operator             │ │
│  │                         │   │  ┌──────────────────────────────────┐  │ │
│  │ ┌─────────────────────┐ │   │  │     GarageCluster Controller     │  │ │
│  │ │ GarageCluster       │ │   │  ├──────────────────────────────────┤  │ │
│  │ │ S3Connection        │ │   │  │      S3Connection Controller     │  │ │
│  │ └─────────▲───────────┘ │   │  ├──────────────────────────────────┤  │ │
│  │           │             │   │  │         Bucket Controller        │  │ │
│  │ ┌─────────┴───────────┐ │   │  ├──────────────────────────────────┤  │ │
│  │ │ - Bucket            │ │   │  │       AccessKey Controller       │  │ │
│  │ │ - AccessKey         │ │   │  ├──────────────────────────────────┤  │ │
│  │ │ - BucketAccess      │ │   │  │      BucketAccess Controller     │  │ │
│  │ └─────────────────────┘ │   │  └──────────────────────────────────┘  │ │
│  └─────────────────────────┘   └───────────────────┬────────────────────┘ │
│                                                    │                      │
│                                  ┌─────────────────▼─────────────────┐    │
│                                  │  Garage (AboutBits Helm chart)    │    │
│                                  │  Admin API :3903 · S3 API :3900   │    │
│                                  └───────────────────────────────────┘    │
└───────────────────────────────────────────────────────────────────────────┘
```

The operator talks to Garage only through its [Admin API v2](https://garagehq.deuxfleurs.fr/documentation/reference-manual/admin-api/). The S3 API cannot do this job: it has no notion of access keys, and Garage implements neither IAM nor bucket policies — its permissions are per key and per bucket, and only reachable through the Admin API.

The work is split with the Helm chart like this:

| Concern                                                                        | Owner                                                         |
|--------------------------------------------------------------------------------|---------------------------------------------------------------|
| StatefulSet, volumes, `garage.toml`, the admin token, Services                 | [Garage Helm chart](https://github.com/aboutbits/helm-garage) |
| Cluster layout — a fresh Garage node serves no S3 traffic until one is applied | Operator, through `GarageCluster`                             |
| Buckets, access keys, permissions                                              | Operator, through `Bucket`, `AccessKey` and `BucketAccess`    |

The chart's own `bootstrap` option (one bucket and one key on first boot) is a testing convenience. In production, leave it off and declare those as Custom Resources instead.

## Installation

### Helm Chart

```bash
helm install garage-operator https://github.com/aboutbits/garage-operator/releases/download/v0.1.0/garage-operator-0.1.0.tgz
```

With the Helm chart, the Custom Resource Definitions (CRDs) are installed automatically on the first install. However, if you deploy the operator directly from the OCI image, the CRDs are not automatically applied and must be installed separately. The CRD manifests are attached to every
[release](https://github.com/aboutbits/garage-operator/releases/latest).

### Configuration

The most relevant Helm values:

| Value                                                               | Default                  | Description                                                                               |
|---------------------------------------------------------------------|--------------------------|-------------------------------------------------------------------------------------------|
| `app.replicas`                                                      | `1`                      | Keep this at 1. The operator has no leader election, see [Operations](#a-single-replica). |
| `app.resources`                                                     | 50m / 300Mi, limit 512Mi | Requests and limits of the operator container.                                            |
| `app.imagePullSecrets`                                              | `[]`                     | Pull secrets, if the image is mirrored to a private registry.                             |
| `app.affinity`                                                      | `{}`                     | Scheduling affinity of the operator Pod.                                                  |
| `app.volumes` / `app.volumeMounts`                                  | `[]`                     | Additional volumes for the operator Pod.                                                  |
| `app.envs.QUARKUS_LOG_CONSOLE_JSON_ENABLED`                         | `false`                  | Log as JSON, for log aggregation.                                                         |
| `app.envs.QUARKUS_LOG_CONSOLE_JSON_LOG_FORMAT`                      | `ECS`                    | The JSON format: `DEFAULT`, `ECS` or `GCP`.                                               |
| `app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_<CONTROLLER>_NAMESPACES` | `JOSDK_ALL_NAMESPACES`   | The namespaces a controller watches, see below.                                           |

The chart ships a `README.md` and a `values.schema.json` with the complete list.

#### Watching selected namespaces

By default, every controller watches all namespaces. To restrict the operator to some of them, set the same comma-separated list for all five controllers — `GARAGECLUSTERRECONCILER`, `S3CONNECTIONRECONCILER`,
`BUCKETRECONCILER`, `ACCESSKEYRECONCILER` and `BUCKETACCESSRECONCILER`:

```yaml
app:
  envs:
    QUARKUS_OPERATOR_SDK_CONTROLLERS_GARAGECLUSTERRECONCILER_NAMESPACES: acme,globex
    QUARKUS_OPERATOR_SDK_CONTROLLERS_S3CONNECTIONRECONCILER_NAMESPACES: acme,globex
    QUARKUS_OPERATOR_SDK_CONTROLLERS_BUCKETRECONCILER_NAMESPACES: acme,globex
    QUARKUS_OPERATOR_SDK_CONTROLLERS_ACCESSKEYRECONCILER_NAMESPACES: acme,globex
    QUARKUS_OPERATOR_SDK_CONTROLLERS_BUCKETACCESSRECONCILER_NAMESPACES: acme,globex
```

The RBAC the chart installs stays cluster-wide either way.

## Upgrading

**Helm never upgrades CRDs.** It installs the ones in the chart on the first `helm install` and leaves them alone afterwards, so a new release can bring CRD changes — new fields, new validation rules — that a plain
`helm upgrade` silently skips. Apply the CRDs of the new release first, then upgrade the chart:

```bash
# The release you are upgrading to.
VERSION=0.1.0

for crd in garageclusters s3connections buckets accesskeys bucketaccesses; do
  kubectl apply --server-side --force-conflicts \
    -f "https://github.com/aboutbits/garage-operator/releases/download/v${VERSION}/${crd}.garage.aboutbits.it-v1.yml"
done

helm upgrade garage-operator "https://github.com/aboutbits/garage-operator/releases/download/v${VERSION}/garage-operator-${VERSION}.tgz"
```

The operator Deployment uses the `Recreate` strategy, so there is a short gap without an operator during the upgrade. Nothing is lost in that gap: changes made in the meantime are reconciled as soon as the new Pod is up.

## Security model

**The Custom Resources of this operator are meant for cluster administrators only.** Do not grant tenants RBAC on the `garage.aboutbits.it` API group. The chart does not aggregate these CRDs into the built-in `admin`, `edit` or
`view` roles, so nobody but cluster administrators can create them unless you grant it explicitly.

The reasons:

- The operator reads Secrets with its own, cluster-wide permissions. An `S3Connection` or `GarageCluster` may reference a Secret in any namespace and names the endpoint the token is sent to. Whoever can create one can therefore make the operator read a Secret they cannot read themselves and send it to an endpoint of their choice.
- References may cross namespaces. A `BucketAccess` can grant a key access to a `Bucket` in another namespace, and a `Bucket` or `AccessKey` can use the `S3Connection` of another namespace. That is useful for a central Garage, and unsafe in the hands of tenants.

What the operator itself is allowed to do:

| Resource            | Verbs                                               | Why                                                                    |
|---------------------|-----------------------------------------------------|------------------------------------------------------------------------|
| `garage.aboutbits.it/*` | all                                                 | Its own Custom Resources, their status and finalizers.                 |
| `secrets`           | `get`, `list`, `watch`, `create`, `update`, `patch` | Reading admin tokens; writing the credentials Secrets of `AccessKey`s. |
| `namespaces`        | `get`                                               | Recognizing a namespace that is being deleted.                         |

The secret half of an access key only ever ends up in the `AccessKey`'s own Secret, never in a status or a log line.

The Garage Admin API is plain HTTP inside the cluster by default, so the admin token crosses the Pod network unencrypted. If that matters in your cluster, put the Admin API behind TLS and use an `https://` endpoint, or restrict access to port 3903 with a NetworkPolicy.

## Usage

Further documentation of each Custom Resource can be found here:

- [GarageCluster](docs/garage-cluster.md) – Bootstrap and converge the cluster layout of a Garage installation.
- [S3Connection](docs/s3-connection.md) – Define the backend everything else lives on.
- [Bucket](docs/bucket.md) – Manage buckets, their quotas and what happens when they are deleted.
- [AccessKey](docs/access-key.md) – Manage access keys, with their credentials written to a Secret.
- [BucketAccess](docs/bucket-access.md) – Grant an access key `read`, `write` or `owner` on a bucket.

All resources live in the API group `garage.aboutbits.it`, version `v1`, and are namespaced.

### Showcase

The following example sets up a Garage installed with the AboutBits Helm chart as release `acme` in the namespace `acme`, and gives an application read and write access to one bucket. The chart names its Services and its admin Secret after the release, so the admin API is `acme-garage:3903`, S3 is `acme-garage:3900`, and the token is the `admin_token` key of the Secret `acme-garage`.

```yaml
# Assign the cluster layout. A fresh Garage node serves no S3 traffic until this is done.
---
apiVersion: garage.aboutbits.it/v1
kind: GarageCluster
metadata:
  name: acme
  namespace: acme
spec:
  adminEndpoint: http://acme-garage.acme.svc:3903
  adminSecretRef:
    name: acme-garage
    key: admin_token
  layout:
    zone: default
    # Should match the size of the chart's data volume.
    capacity: 20Gi

# Define the backend that buckets and keys live on. It becomes READY once the layout is applied.
---
apiVersion: garage.aboutbits.it/v1
kind: S3Connection
metadata:
  name: acme
  namespace: acme
spec:
  adminEndpoint: http://acme-garage.acme.svc:3903
  adminSecretRef:
    name: acme-garage
    key: admin_token

# Create a bucket.
---
apiVersion: garage.aboutbits.it/v1
kind: Bucket
metadata:
  name: acme-uploads
  namespace: acme
spec:
  connectionRef:
    name: acme
  name: acme-uploads
  reclaimPolicy: Retain
  quotas:
    maxSize: 10Gi

# Create an access key. The credentials are written to the Secret `acme-app-s3`.
---
apiVersion: garage.aboutbits.it/v1
kind: AccessKey
metadata:
  name: acme-app
  namespace: acme
spec:
  connectionRef:
    name: acme
  name: acme-app
  secretName: acme-app-s3

# Let the key read and write the bucket.
---
apiVersion: garage.aboutbits.it/v1
kind: BucketAccess
metadata:
  name: acme-app-uploads
  namespace: acme
spec:
  bucketRef:
    name: acme-uploads
  accessKeyRef:
    name: acme-app
  permissions:
    - read
    - write
```

The application then reads its credentials from the Secret. The S3 endpoint and region are not part of it:

```yaml
env:
  - name: AWS_ACCESS_KEY_ID
    valueFrom:
      secretKeyRef:
        name: acme-app-s3
        key: accessKeyId
  - name: AWS_SECRET_ACCESS_KEY
    valueFrom:
      secretKeyRef:
        name: acme-app-s3
        key: secretAccessKey
  - name: AWS_ENDPOINT_URL_S3
    value: http://acme-garage.acme.svc:3900
  # `s3_region` in garage.toml; the AboutBits Helm chart uses `garage`.
  - name: AWS_REGION
    value: garage
```

Garage addresses buckets by path, so clients need path-style access enabled (`forcePathStyle` in the AWS SDKs).

## Operations

### Status

Every resource reports a `phase` and a `message`, which `kubectl get` shows as columns:

```bash
kubectl get garageclusters,s3connections,buckets,accesskeys,bucketaccesses --all-namespaces
```

| Phase      | Meaning                                                                                              |
|------------|------------------------------------------------------------------------------------------------------|
| `PENDING`  | Waiting for something it depends on, for example an `S3Connection` that is not `READY` yet. Retried. |
| `READY`    | In sync with the backend.                                                                            |
| `ERROR`    | Something needs attention; the `message` says what. Retried, usually every minute.                   |
| `DELETING` | Cleanup on the backend is in progress, or blocked — again, see the `message`.                        |

Resources depend on each other in this order, and each one waits for the one before it to be `READY`:
`GarageCluster` → `S3Connection` → `Bucket` and `AccessKey` → `BucketAccess`. Everything can be applied at once; it settles on its own.

### Drift

The operator reconciles every resource at least every 10 minutes, even when nothing changed in Kubernetes. A bucket or key deleted by hand in Garage is recreated, changed quotas or permissions are put back, and an
`S3Connection` whose backend went down leaves `READY`.

The credentials Secret of an `AccessKey` is watched directly: deleted or edited, it is written again within seconds, with the same credentials.

### Deleting resources

| Resource        | Deleting it                                                                                        |
|-----------------|----------------------------------------------------------------------------------------------------|
| `GarageCluster` | Only removes the operator's record of the cluster. The layout and the data stay.                   |
| `S3Connection`  | Removes the connection. Delete it **last**: deleting the resources on it needs it.                 |
| `Bucket`        | `Retain` (default) keeps the bucket. `Delete` deletes it — but never one that still holds objects. |
| `AccessKey`     | Always deletes the key, and with it every grant it held. The Secret goes too.                      |
| `BucketAccess`  | Revokes the key's permissions on the bucket.                                                       |

A resource whose backend cleanup cannot proceed keeps its finalizer and says why in its `message`, rather than silently leaving something behind.

### Offboarding a namespace

The safest way to remove a customer is to delete their `Bucket`, `AccessKey` and `BucketAccess` resources first — `terraform destroy` does this in dependency order — and the namespace afterwards.

Deleting the namespace right away works too. When Garage is installed in that namespace, Garage, its admin Secret and the `S3Connection` disappear at the same time as everything else, and waiting for them would keep the namespace in `Terminating` forever. So once a namespace is being deleted and its `S3Connection` is gone, the operator releases the remaining resources without cleaning up on the backend. If the backend lives in another namespace, the keys, buckets and grants of the deleted namespace then stay there and have to be removed in Garage by hand.

### Rotating credentials

There is no rotation trigger yet. Delete the `AccessKey` and apply it again: Garage issues a new key, the operator writes it to the Secret, and every `BucketAccess` for it grants the new key. Applications have to pick up the changed Secret, typically through a restart. See [AccessKey](docs/access-key.md#rotation).

### A single replica

The operator runs as a single replica without leader election. Two instances would reconcile the same resources concurrently, and Garage key names are not unique, so they could create duplicate keys. Keep `app.replicas` at 1; the `Recreate` strategy makes sure an upgrade never overlaps the old and the new Pod.

### Health and metrics

| Endpoint          | What it reports                                                                                                                                                                                               |
|-------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/q/health/live`  | The operator process is alive.                                                                                                                                                                                |
| `/q/health/ready` | The operator is running and its controllers are healthy. It deliberately does **not** probe the Garage backends: one unreachable Garage must not block the operator. Backend state is on each `S3Connection`. |
| `/q/metrics`      | Prometheus metrics. The Pod carries the `prometheus.io/*` scrape annotations.                                                                                                                                 |

## Limitations

- `GarageCluster` assigns layouts for **single-node** clusters only, which is what the AboutBits Helm chart deploys. On a larger cluster it reports `ERROR` instead of guessing zones and capacities.
- No credential rotation trigger, no lifecycle rules and no website configuration yet.

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

You can run the operator in dev mode, against a throwaway k3s cluster and a real Garage node, using:

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

The tests are `@QuarkusTest` integration tests against real infrastructure provided by Quarkus Dev Services: a k3s cluster with the CRDs applied, and a Garage node started from `operator/src/main/docker/compose-devservices.yml`. The Garage image there is pinned to the version the Helm chart deploys, so the Admin API contract is exercised against the real thing.

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
