# AccessKey

An `AccessKey` creates a set of S3 credentials on the backend named by its
[`S3Connection`](s3-connection.md), and writes them into a Secret it owns.

On its own an access key can do nothing: it has no access to any bucket until a
`BucketAccess` grants it some.

## Example

```yaml
apiVersion: garage.aboutbits.it/v1
kind: AccessKey
metadata:
  name: acme-app
  namespace: acme
spec:
  connectionRef:
    name: acme
  name: acme-app
  secretName: acme-app-s3          # optional, defaults to metadata.name
  allowCreateBucket: false
```

## Spec

| Field                     | Required | Description                                                        |
|---------------------------|----------|--------------------------------------------------------------------|
| `connectionRef.name`      | yes      | The `S3Connection` the key lives on. **Immutable.**                |
| `connectionRef.namespace` | no       | Defaults to the namespace of the `AccessKey`.                      |
| `name`                    | yes      | The name the key is created under on the backend. **Immutable.**   |
| `secretName`              | no       | Name of the Secret to write. Defaults to `metadata.name`.          |
| `allowCreateBucket`       | no       | Whether the key may create buckets by itself. Defaults to `false`. |

`name` is immutable because it is how the operator finds the key the first time: a change would leave the original key in place, with its grants, and create a second one. `connectionRef` is immutable for the same reason — pointing the resource at another backend would create a second key there and leave the first one behind as a live credential.

`allowCreateBucket` is off by default on purpose. Buckets are meant to be declared as
[`Bucket`](bucket.md) resources; a key that can create its own can grow storage outside of what is written down.

## The generated Secret

The operator writes an `Opaque` Secret with two entries:

| Key               | Value                                              |
|-------------------|----------------------------------------------------|
| `accessKeyId`     | The `AWS_ACCESS_KEY_ID` clients authenticate with. |
| `secretAccessKey` | The secret half of the credentials.                |

The Secret is always created **in the `AccessKey`'s own namespace**, and carries an owner reference to the `AccessKey`, so Kubernetes garbage-collects it when the resource goes away. Kubernetes ignores cross-namespace owner references, which is why `secretName` names a Secret rather than referencing an arbitrary namespace — a Secret elsewhere would outlive its owner while still holding live credentials.

If a Secret of that name already exists and is not owned by the `AccessKey`, the operator leaves it untouched and reports `ERROR`. Taking it over would hand it to the `AccessKey`'s lifecycle, and Kubernetes would garbage-collect it — with whatever else it holds — when the `AccessKey` is deleted.

Note the Secret does **not** carry the S3 endpoint; that lives on the `S3Connection`, and the connection currently records only the admin endpoint.

### If the Secret is deleted

It is written again on the next reconciliation, with the **same** credentials — Garage reveals the secret key on every read, so the operator can rebuild the Secret rather than having to rotate the key. Deleting the Secret is therefore recoverable and does not break running clients.

## Status

| Field         | Description                                    |
|---------------|------------------------------------------------|
| `phase`       | `PENDING`, `READY`, `ERROR`, `DELETING`        |
| `accessKeyId` | The key identifier.                            |
| `secretName`  | The Secret the credentials were written to.    |
| `message`     | Human-readable detail about the current state. |

The secret half of the credentials is deliberately **not** in the status: a status is readable by anyone who can read the resource, while the Secret is protected by RBAC.

An `AccessKey` stays `PENDING` while its `S3Connection` does not exist or is not `READY`, and nothing is created on the backend in that state.

## Adoption

A key that already exists on the backend under the same name is adopted rather than duplicated, and its credentials are written into the Secret. This makes it safe to declare an `AccessKey` for a key that predates the operator, including one created by the Helm chart's `bootstrap` option.

The name is only used to find the key the first time. Once it is recorded in `status.accessKeyId`, the operator addresses the key by that identifier alone: renaming it on the backend does not detach it, and deleting the resource deletes exactly that key.

Garage does not enforce unique key names, so adoption is refused with `ERROR` when

- more than one key on the backend carries the name, or
- the key is already managed by another `AccessKey` resource.

In both cases nothing is written to the Secret, and deleting the refused resource leaves the existing keys alone.

## Deletion

Unlike [`Bucket`](bucket.md), an `AccessKey` has **no reclaim policy**: deleting the resource always deletes the key, and with it every grant the key held. Credentials hold no data, so nothing is lost by removing them, while a key left behind is a live credential nobody is tracking any more. This matches how the sibling PostgreSQL Operator treats roles.

The Secret goes with it, through its owner reference.

If the resource never got as far as creating a key — it stayed `PENDING` because its `S3Connection`
was missing — deletion completes immediately rather than waiting for a connection that may never appear.

Otherwise a missing `S3Connection` keeps the resource around until the connection is back, so the key is not silently left behind. The one exception is deleting the **whole namespace**: when Garage is installed in the same namespace, the connection, its admin Secret and Garage itself all go away at once, and waiting for them would keep the namespace in `Terminating` forever. Once the namespace is being deleted and the connection is gone, the resource is released without deleting the key. A backend that lives in another namespace then keeps the key, and it has to be removed there by hand.

## Rotation

There is no rotation trigger yet. To rotate credentials today, delete the `AccessKey` and apply it again: the backend issues a new key and the operator writes the new credentials into the Secret. Clients have to re-read the Secret, and any `BucketAccess` for the key is reconciled back.

Kubernetes collects the old Secret in the background. Until it has, the new `AccessKey` finds a Secret it does not own, reports `ERROR`, and tries again a minute later.
