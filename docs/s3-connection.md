# S3Connection

An `S3Connection` names a Garage Admin API and the token to administer it. Buckets, access keys and permissions reference a connection, which tells the operator where to create them.

The operator's work is almost entirely **admin-plane**, and the admin plane is not part of the S3 API: Garage has no IAM and no bucket policies, and manages buckets, keys and their per-key, per-bucket grants through its Admin API only.

## Relationship to GarageCluster

For Garage the two are siblings, not alternatives:

| Resource                             | Answers                                                     |
|--------------------------------------|-------------------------------------------------------------|
| [`GarageCluster`](garage-cluster.md) | Is the cluster itself set up? (layout assigned and applied) |
| `S3Connection`                       | Can I administer object storage on this backend right now?  |

A connection only reports `READY` when the backend can actually serve requests. A Garage node that answers its Admin API but has no layout reports health `unavailable`, and the connection stays
`PENDING` with a message pointing at the `GarageCluster`. Because `Bucket` and key resources gate on their connection being `READY`, that one check keeps them from acting against a cluster that is not finished bootstrapping.

Both resources reference the same admin endpoint and Secret. That duplication is deliberate — each is self-contained, and a backend the operator only *uses* does not need a `GarageCluster` at all.

## Example

```yaml
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
```

## Spec

| Field                      | Required | Description                                                                                               |
|----------------------------|----------|-----------------------------------------------------------------------------------------------------------|
| `adminEndpoint`            | yes      | Base URL of the Garage **Admin** API, port 3903. This is not the S3 endpoint clients use.                 |
| `adminSecretRef.name`      | yes      | Name of the Secret holding the admin credentials.                                                         |
| `adminSecretRef.namespace` | no       | Defaults to the namespace of the `S3Connection`.                                                          |
| `adminSecretRef.key`       | yes      | Key within the Secret. The AboutBits Garage chart uses `admin_token`.                                     |

## Status

| Phase     | Meaning                                                                                                                                                 |
|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `READY`   | The backend is reachable, the credentials are accepted, and it can serve requests.                                                                      |
| `PENDING` | Reachable and authenticated, but not ready — for Garage, health is `unavailable`, usually because no layout has been applied. Retried every 30 seconds. |
| `ERROR`   | Unreachable, or the credentials were rejected, or the Secret is missing.                                                                                |

`message` carries the backend detail, for example
`Garage cluster healthy [storageNodes=1, storageNodesUp=1, partitionsAllOk=256/256]`.

Note that Garage's `degraded` status still counts as `READY`: a quorum of write nodes is available, so reads and writes succeed. Only `unavailable` blocks.

## Health endpoint

The operator's readiness endpoint does **not** probe backends. One unreachable Garage would otherwise make the operator itself unready and block its rollout, although the operator is fine and every other backend is still being served. The state of each backend is reported on its `S3Connection` instead.
