# GarageClusterConnection

A `GarageClusterConnection` names a Garage Admin API and the token to administer it. Buckets, access keys and permissions reference a connection, which tells the operator where to create them.

The operator's work is almost entirely **admin-plane**, and the admin plane is not part of the S3 API: Garage has no IAM and no bucket policies, and manages buckets, keys and their per-key, per-bucket grants through its Admin API only.

## Relationship to GarageCluster

For Garage the two are siblings, not alternatives:

| Resource                             | Answers                                                     |
|--------------------------------------|-------------------------------------------------------------|
| [`GarageCluster`](garage-cluster.md) | Is the cluster itself set up? (layout assigned and applied) |
| `GarageClusterConnection`            | Can I administer object storage on this backend right now?  |

A connection only reports `READY` when the backend can actually serve requests. A Garage node that answers its Admin API but has no layout reports health `unavailable`, and the connection stays
`PENDING` with a message pointing at the `GarageCluster`. Because [`GarageBucket`](garage-bucket.md) and key resources gate on their connection being `READY`, that one check keeps them from acting against a cluster that is not finished bootstrapping.

Both resources reference the same admin endpoint and Secret. That duplication is deliberate — each is self-contained, and a backend the operator only *uses* does not need a `GarageCluster` at all.

## Example

```yaml
apiVersion: garage.aboutbits.it/v1
kind: GarageClusterConnection
metadata:
  namespace: acme
  name: acme
spec:
  adminEndpoint: http://acme-garage.acme.svc:3903
  adminSecretRef:
    name: acme-garage-admin
    key: GARAGE_ADMIN_TOKEN
```

## Spec

| Field            | Type                                           | Description                                                                                                                                                                                                     | Required | Mutable |
|------------------|------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|---------|
| `adminEndpoint`  | `string`                                       | Base URL of the Garage **Admin** API, port 3903. This is not the S3 endpoint clients use.                                                                                                                       | Yes      | No      |
| `adminSecretRef` | [`SecretKeyRef`](#secretkeyref-adminsecretref) | Reference to the `Opaque` Secret holding the Admin API bearer token: the Secret set as `admin.existingSecret.name` of the chart. If the chart reads the token from a file, create a Secret with the same token. | Yes      | Yes     |

### SecretKeyRef (`adminSecretRef`)

| Field       | Type     | Description                                                                                                  | Required |
|-------------|----------|--------------------------------------------------------------------------------------------------------------|----------|
| `namespace` | `string` | Namespace of the Secret. If not specified, uses the namespace of the `GarageClusterConnection`.              | No       |
| `name`      | `string` | Name of the Secret.                                                                                          | Yes      |
| `key`       | `string` | Key within the Secret: the same as `admin.existingSecret.key` of the chart, by default `GARAGE_ADMIN_TOKEN`. | Yes      |

`adminEndpoint` is immutable, because buckets and access keys record the ids that they get from this Garage. A changed endpoint would create second ones on another Garage. Delete and recreate the `GarageClusterConnection` to point it at a different Garage. `adminSecretRef` can change, for example to rotate the token. After the token changes in the Secret, restart the Garage Pod: Garage reads the token only at startup, see the chart readme.

The token needs access to these Admin API endpoints:

| Used by                                         | Endpoints                                                                      |
|-------------------------------------------------|--------------------------------------------------------------------------------|
| `GarageClusterConnection`                       | `GetClusterHealth`                                                             |
| [`GarageBucket`](garage-bucket.md)              | `ListBuckets`, `GetBucketInfo`, `CreateBucket`, `UpdateBucket`, `DeleteBucket` |
| [`GarageAccessKey`](garage-access-key.md)       | `ListKeys`, `GetKeyInfo`, `CreateKey`, `UpdateKey`, `DeleteKey`                |
| [`GarageBucketAccess`](garage-bucket-access.md) | `ListBuckets`, `GetBucketInfo`, `AllowBucketKey`, `DenyBucketKey`              |

The admin token set as `admin.existingSecret` of the chart has access to all endpoints. A Garage admin token with a smaller scope must list the endpoints that it allows.

## Status

| Phase     | Meaning                                                                                                                                                 |
|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `READY`   | The backend is reachable, the credentials are accepted, and it can serve requests.                                                                      |
| `PENDING` | Reachable and authenticated, but not ready — for Garage, health is `unavailable`, usually because no layout has been applied. Retried every 30 seconds. |
| `ERROR`   | Unreachable, or the credentials were rejected, or the Secret is missing. Retried every 60 seconds.                                                      |

`message` carries the backend detail, for example
`Garage cluster healthy [storageNodes=1, storageNodesUp=1, partitionsAllOk=256/256]`.

Note that Garage's `degraded` status still counts as `READY`: a quorum of write nodes is available, so reads and writes succeed. Only `unavailable` blocks.

## Health endpoint

The operator's readiness endpoint does **not** probe backends. One unreachable Garage would otherwise make the operator itself unready and block its rollout, although the operator is fine and every other backend is still being served. The state of each backend is reported on its `GarageClusterConnection` instead.
