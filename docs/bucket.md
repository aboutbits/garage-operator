# Bucket

A `Bucket` manages one bucket on the backend named by its [`S3Connection`](s3-connection.md).

## Example

```yaml
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
    maxObjects: 100000
```

## Spec

| Field                     | Required | Description                                                         |
|---------------------------|----------|---------------------------------------------------------------------|
| `connectionRef.name`      | yes      | The `S3Connection` the bucket lives on. **Immutable.**              |
| `connectionRef.namespace` | no       | Defaults to the namespace of the `Bucket`.                          |
| `name`                    | yes      | The name the bucket is addressed by over S3. **Immutable.**         |
| `reclaimPolicy`           | no       | `Retain` (default) or `Delete`.                                     |
| `quotas.maxSize`          | no       | Maximum total object size, as a Kubernetes quantity such as `10Gi`. |
| `quotas.maxObjects`       | no       | Maximum number of objects.                                          |

`name` follows the S3 bucket naming rules — 3 to 63 characters of lowercase letters, digits, dots and hyphens, starting and ending with a letter or digit — and is enforced by the CRD schema.

It is immutable because no supported backend can rename a bucket: accepting a change would orphan the original and quietly create a second one. Delete and recreate the `Bucket` to use a different name. `connectionRef` is immutable for the same reason: moving the resource to another backend would create a second bucket there.

## Status

| Field               | Description                                                                      |
|---------------------|----------------------------------------------------------------------------------|
| `phase`             | `PENDING`, `READY`, `ERROR`, `DELETING`                                          |
| `bucketId`          | Backend-assigned identifier. Operations address the bucket by this, not by name. |
| `objects` / `bytes` | What the bucket currently holds, as last observed.                               |
| `message`           | Human-readable detail about the current state.                                   |

A `Bucket` stays `PENDING` while its `S3Connection` does not exist or is not `READY`, and is retried every 60 seconds. Nothing is created on the backend in that state, so a bucket is never created against a cluster that has no layout applied.

Note that `objects` and `bytes` are a snapshot from the last reconciliation, not a live gauge — they are refreshed when the resource is reconciled, not as data is written.

## Quotas

Quotas are declarative: the spec is the whole truth. Setting `quotas.maxObjects` raises or lowers the limit, and **removing the `quotas` section lifts the limits entirely** rather than leaving the previous values in place.

Everything else about the bucket — CORS rules, lifecycle rules, website access — is left untouched, so anything configured out of band survives reconciliation.

## Adoption

A bucket that already exists on the backend under the same name is adopted, not reported as a conflict. This makes it safe to declare a `Bucket` for storage that predates the operator, such as one created by the Helm chart's `bootstrap` option.

The flip side is that the operator does not track whether it created the bucket. Combined with
`reclaimPolicy: Delete`, an adopted bucket will be deleted along with the CR.

The name is only used to find the bucket the first time. Once it is recorded in `status.bucketId`, the operator addresses the bucket by that identifier alone, so a name moved to a different bucket on the backend cannot redirect quota updates or deletion.

A bucket that another `Bucket` resource already manages is not adopted a second time: the second resource reports `ERROR`, and deleting it leaves the bucket alone. Two resources sharing a bucket would fight over its quotas, and either one could delete it out from under the other.

## Deletion

| `reclaimPolicy`    | Deleting the `Bucket` CR                           |
|--------------------|----------------------------------------------------|
| `Retain` (default) | The CR goes away, the bucket and its objects stay. |
| `Delete`           | The bucket is deleted from the backend.            |

**A bucket that still holds objects is never deleted**, whatever the policy says. Garage refuses the request, and the operator reports it rather than forcing the issue:

```text
Deletion failed: the bucket still holds objects. Empty it, or set reclaimPolicy to Retain
to release the resource without deleting it.
```

The CR keeps its finalizer and stays visible in that state, retrying every 60 seconds, so a deletion that cannot proceed is a visible decision rather than silent data loss. To resolve it, either empty the bucket or switch `reclaimPolicy` to `Retain`.

If the bucket is already gone from the backend, deletion of the CR completes normally. The same is true if the bucket was never created, because the `Bucket` stayed `PENDING` waiting for its
`S3Connection` — deletion then completes immediately rather than waiting for a connection that may never appear.

Otherwise, a `Delete` bucket whose `S3Connection` is missing keeps the CR around until the connection is back. The one exception is deleting the **whole namespace**: when Garage is installed in the same namespace, the connection and Garage itself go away at once, and waiting for them would keep the namespace in `Terminating` forever. Once the namespace is being deleted and the connection is gone, the CR is released without deleting the bucket. No data is lost that way; a backend that lives in another namespace simply keeps the bucket.
