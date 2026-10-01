# GarageCluster

A `GarageCluster` bootstraps and converges the **cluster layout** of a Garage installation.

This is the resource everything else depends on. A freshly installed Garage node knows about itself but has no role in any layout, and in that state it **serves no S3 traffic at all** — every request fails until a layout is assigned and applied. The
[AboutBits Garage Helm chart](https://github.com/aboutbits/helm-garage) deliberately stops at day-0 concerns (StatefulSet, volumes, `garage.toml`, the admin token), so assigning that layout is the operator's job.

The operator drives Garage through its
[Admin API v2](https://garagehq.deuxfleurs.fr/documentation/reference-manual/admin-api/), not through the S3 API — Garage implements neither IAM nor bucket policies, so buckets, keys and permissions are all Admin API concerns too.

Once the layout is applied, an [`S3Connection`](s3-connection.md) pointed at the same cluster becomes
`READY`, which is what unblocks buckets and access keys.

## Scope

This version manages **single-node clusters**, which is what the Helm chart deploys (`replication_factor = 1`, no clustering or node discovery). If the cluster reports more than one node, the operator refuses to touch the layout and reports `ERROR` rather than guessing a zone and capacity per node.

Deleting a `GarageCluster` removes the operator's record of the cluster. It does **not** tear the storage cluster down: removing a node's role would trigger a rebalance and, on a single-node cluster, discard the data. There is therefore no reclaim policy on this resource.

## Example

```yaml
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
    capacity: 20Gi
    tags:
      - production
```

## Spec

| Field                      | Required | Description                                                                                                                                                                                               |
|----------------------------|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `adminEndpoint`            | yes      | Base URL of the Garage Admin API. The Helm chart exposes it on port 3903 as a ClusterIP-only Service, reachable from inside the cluster only.                                                             |
| `adminSecretRef.name`      | yes      | Name of the `Opaque` Secret holding the Admin API bearer token. The chart creates one named after the release.                                                                                            |
| `adminSecretRef.namespace` | no       | Defaults to the namespace of the `GarageCluster`.                                                                                                                                                         |
| `adminSecretRef.key`       | yes      | Key within the Secret. The chart stores the token under `admin_token`.                                                                                                                                    |
| `layout.zone`              | yes      | Zone the node is placed in. With `replication_factor = 1` this is effectively a label.                                                                                                                    |
| `layout.capacity`          | yes      | Storage capacity to advertise, as a Kubernetes quantity such as `20Gi`. Should match the size of the chart's data volume. Garage uses it to weight partition distribution; it is not enforced as a limit. |
| `layout.tags`              | no       | Tags to attach to the node. Informational; order is not significant.                                                                                                                                      |

Note that the CRD schema carries no defaults — every required field has to be spelled out in the manifest.

## Status

| Field                             | Description                                                                    |
|-----------------------------------|--------------------------------------------------------------------------------|
| `phase`                           | `PENDING`, `READY`, `ERROR`                                                    |
| `layoutVersion`                   | Version number of the applied cluster layout. `0` means none has been applied. |
| `nodeId`                          | Full-length identifier of the Garage node.                                     |
| `health`                          | Cluster health as reported by Garage: `healthy`, `degraded` or `unavailable`.  |
| `storageNodes` / `storageNodesUp` | Storage nodes in the layout, and how many are connected.                       |
| `message`                         | Human-readable detail about the current state.                                 |

`PENDING` is the normal state right after a `helm install`, while Garage is still starting and reports no nodes or a disconnected node; it is retried every 15 seconds.

## How reconciliation works

1. Read `GetClusterStatus`. No nodes, or the node not connected, means Garage is still coming up →
   `PENDING`, retry.
2. Compare the node's assigned role against the spec. Tags are compared as a set, and capacity is compared in bytes, so a reordered tag list or `20Gi` versus `20Gi` is not a change.
3. If they match, stop — nothing is written to Garage.
4. Otherwise read `GetClusterLayout` for the authoritative version number, stage the role with
   `UpdateClusterLayout`, then commit it with `ApplyClusterLayout` at version + 1.
5. Read `GetClusterHealth` to fill in the status, and report `READY`.

Because step 2 short-circuits, reconciliation is idempotent: a `GarageCluster` whose layout already matches never creates a new layout version, no matter how often it is reconciled.
