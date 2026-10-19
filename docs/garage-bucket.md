# GarageBucket

A `GarageBucket` manages one bucket on the backend named by its [`GarageClusterConnection`](garage-cluster-connection.md).

## Example

```yaml
apiVersion: garage.aboutbits.it/v1
kind: GarageBucket
metadata:
  namespace: acme
  name: acme-uploads
spec:
  connectionRef:
    name: acme
  name: acme-uploads
  reclaimPolicy: Retain
  quotas:
    maxSize: 10Gi
    maxObjects: 100000
```

## Spec

| Field           | Type                                                       | Description                                                     | Required | Mutable |
|-----------------|------------------------------------------------------------|-----------------------------------------------------------------|----------|---------|
| `connectionRef` | [`ResourceRef`](#resourceref-connectionref)                | Reference to the `GarageClusterConnection` the bucket lives on. | Yes      | No      |
| `name`          | `string`                                                   | The name the bucket is addressed by over S3.                    | Yes      | No      |
| `reclaimPolicy` | [`ReclaimPolicy`](#deletion)                               | `Retain` (default) or `Delete`.                                 | No       | Yes     |
| `quotas`        | [`GarageBucketQuotasSpec`](#garagebucketquotasspec-quotas) | Limits of the bucket. Omit for an unlimited bucket.             | No       | Yes     |

### ResourceRef (`connectionRef`)

| Field       | Type     | Description                                                                                             | Required |
|-------------|----------|---------------------------------------------------------------------------------------------------------|----------|
| `namespace` | `string` | Namespace of the `GarageClusterConnection`. If not specified, uses the namespace of the `GarageBucket`. | No       |
| `name`      | `string` | Name of the `GarageClusterConnection`.                                                                  | Yes      |

### GarageBucketQuotasSpec (`quotas`)

| Field        | Type      | Description                                                                                           | Required |
|--------------|-----------|-------------------------------------------------------------------------------------------------------|----------|
| `maxSize`    | `string`  | Maximum total object size, as a Kubernetes quantity of whole bytes greater than zero, such as `10Gi`. | No       |
| `maxObjects` | `integer` | Maximum number of objects, at least `1`.                                                              | No       |

A change to a `Mutable` field is applied at the next reconcile.

`name` follows the S3 bucket naming rules — 3 to 63 characters of lowercase letters, digits, dots and hyphens, starting and ending with a letter or digit — and is enforced by the CRD schema. Like Garage, the schema also refuses a name that is an IP address, starts with `xn--`, contains `.xn--` or ends with `-s3alias`.

It is immutable because no supported backend can rename a bucket: accepting a change would orphan the original and quietly create a second one. Delete and recreate the `GarageBucket` to use a different name. `connectionRef` is immutable for the same reason: moving the resource to another backend would create a second bucket there.

The admin token of the `GarageClusterConnection` needs access to the Admin API endpoints `ListBuckets`, `GetBucketInfo`, `CreateBucket`, `UpdateBucket` and `DeleteBucket`, see [`GarageClusterConnection`](garage-cluster-connection.md#spec).

## Status

| Field               | Description                                                                      |
|---------------------|----------------------------------------------------------------------------------|
| `phase`             | `PENDING`, `READY`, `ERROR`, `DELETING`                                          |
| `bucketId`          | Backend-assigned identifier. Operations address the bucket by this, not by name. |
| `objects` / `bytes` | What the bucket currently holds, as last observed.                               |
| `message`           | Human-readable detail about the current state.                                   |

A `GarageBucket` stays `PENDING` while its `GarageClusterConnection` does not exist or is not `READY`, and is retried every 60 seconds. Nothing is created on the backend in that state, so a bucket is never created against a cluster that has no layout applied.

`ERROR` means that the last reconciliation failed, and `message` has the reason: for example the backend cannot be reached, `quotas.maxSize` is not a valid quantity, or another `GarageBucket` already manages the bucket. The operator tries again every 60 seconds.

Note that `objects` and `bytes` are a snapshot from the last reconciliation, not a live gauge — they are refreshed when the resource is reconciled, not as data is written.

## Quotas

Quotas are declarative: the spec is the whole truth. Setting `quotas.maxObjects` raises or lowers the limit, and **removing the `quotas` section lifts the limits entirely** rather than leaving the previous values in place.

Everything else about the bucket — CORS rules, lifecycle rules, website access — is left untouched, so anything configured out of band survives reconciliation.

## Adoption

A bucket that already exists on the backend under the same name is adopted, not reported as a conflict. This makes it safe to declare a `GarageBucket` for storage that predates the operator, such as one created by the Helm chart's `bootstrap` option. On adoption, the quotas of the bucket are set to the spec: without `quotas`, existing limits are removed.

The flip side is that the operator does not track whether it created the bucket. Combined with `reclaimPolicy: Delete`, an adopted bucket is deleted along with the CR when it is empty.

The name is only used to find the bucket the first time. Once it is recorded in `status.bucketId`, the operator addresses the bucket by that identifier alone, so a name moved to a different bucket on the backend cannot redirect quota updates or deletion. If the recorded bucket is deleted on the backend, the operator looks for the name again. It adopts a bucket of that name or creates a new, empty one.

A bucket that another `GarageBucket` resource already manages is not adopted a second time: the second resource reports `ERROR`, and deleting it leaves the bucket alone. Two resources sharing a bucket would fight over its quotas, and either one could delete it out from under the other.

## Deletion

| `reclaimPolicy`    | Deleting the `GarageBucket` CR                     |
|--------------------|----------------------------------------------------|
| `Retain` (default) | The CR goes away, the bucket and its objects stay. |
| `Delete`           | The bucket is deleted from the backend.            |

**A bucket that still holds objects is never deleted**, whatever the policy says. Garage refuses the request, and the operator reports it rather than forcing the issue:

```text
Deletion failed: the bucket still holds objects. Empty it, or set reclaimPolicy to Retain
to release the resource without deleting it.
```

The CR keeps its finalizer and stays visible in that state, retrying every 60 seconds, so a deletion that cannot proceed is a visible decision rather than silent data loss. To resolve it, either empty the bucket or switch `reclaimPolicy` to `Retain`.

If the bucket is already gone from the backend, deletion of the CR completes normally. The same is true if the bucket was never created, because the `GarageBucket` stayed `PENDING` waiting for its
`GarageClusterConnection` — deletion then completes immediately rather than waiting for a connection that may never appear.

Otherwise, a `Delete` bucket whose `GarageClusterConnection` is missing or not `READY` keeps the CR around until the connection is back. The one exception is deleting the **whole namespace**: when Garage is installed in the same namespace, the connection and Garage itself go away at once, and waiting for them would keep the namespace in `Terminating` forever. Once the namespace is being deleted and the connection is missing or not `READY`, the CR is released without deleting the bucket. No data is lost that way; a backend that lives in another namespace simply keeps the bucket.
