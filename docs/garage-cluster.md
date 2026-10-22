# GarageCluster

A `GarageCluster` bootstraps and converges the **cluster layout** of a Garage installation.

A Garage node started without `--single-node` knows about itself but has no role in any layout, and in that state it **serves no S3 traffic at all** — every request fails until a layout is assigned and applied. The `GarageCluster` assigns that layout.

## Requirement: `garage.singleNode: false`

By default the [AboutBits Garage Helm chart](https://github.com/aboutbits/helm-garage) starts Garage with `--single-node`. Garage then creates the layout itself, and refuses to start once the layout version is higher than 1. A `GarageCluster` applies a new layout version as soon as its spec differs from the role of the node, so with `--single-node` the next restart of the Garage Pod fails for good.

Install the chart with `garage.singleNode: false` to manage the layout with a `GarageCluster`. Without a `GarageCluster`, keep the default: Garage creates its own layout, and [`GarageClusterConnection`](garage-cluster-connection.md), `GarageBucket` and `GarageAccessKey` work the same. The chart's `bootstrap` option needs single-node mode, so with `garage.singleNode: false` create the bucket and the key with a `GarageBucket` and a `GarageAccessKey` instead.

The operator cannot see how Garage was started, so it cannot refuse a `GarageCluster` for a Garage in single-node mode.

The operator drives Garage through its
[Admin API v2](https://garagehq.deuxfleurs.fr/documentation/reference-manual/admin-api/), not through the S3 API — Garage implements neither IAM nor bucket policies, so buckets, keys and permissions are all Admin API concerns too.

Once the layout is applied, a [`GarageClusterConnection`](garage-cluster-connection.md) pointed at the same cluster becomes
`READY`, which is what unblocks buckets and access keys.

## Scope

This version manages **single-node clusters**, which is what the Helm chart deploys (`replication_factor = 1`, no clustering or node discovery). If the cluster reports more than one node, the operator refuses to touch the layout and reports `ERROR` rather than guessing a zone and capacity per node.

Garage keeps one layout per cluster, with a role per node. `spec.layout` is the role that a storage node gets, so on a single node it is the whole layout. On a cluster of identical nodes in one zone, the same role would apply to every node. What only a larger cluster needs, such as a different zone or capacity per node, gateway nodes without capacity, or the zone redundancy of the layout, would be optional additions, and existing `GarageCluster`s would stay valid.

Only one `GarageCluster` manages a Garage node. A second `GarageCluster` for the same node, in any namespace, reports `ERROR` and leaves the layout alone. Otherwise two resources with different layouts would apply their roles in turn, a new layout version at every reconcile. The node is recognized by its id, so two spellings of the same `adminEndpoint` count as the same Garage. When the first `GarageCluster` is deleted, the second one takes over at its next retry.

Deleting a `GarageCluster` removes the operator's record of the cluster. It does **not** tear the storage cluster down: removing a node's role would trigger a rebalance and, on a single-node cluster, discard the data. There is therefore no reclaim policy on this resource.

## Example

```yaml
apiVersion: garage.aboutbits.it/v1
kind: GarageCluster
metadata:
  namespace: acme
  name: acme
spec:
  adminEndpoint: http://acme-garage.acme.svc:3903
  adminSecretRef:
    name: acme-garage-admin
    key: GARAGE_ADMIN_TOKEN
  layout:
    zone: default
    capacity: 20Gi
    tags:
      - production
```

## Spec

| Field            | Type                                                         | Description                                                                                                                                                                                                         | Required | Mutable |
|------------------|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|---------|
| `adminEndpoint`  | `string`                                                     | Base URL of the Garage Admin API, port 3903. The chart puts it on the same Service as the S3 API. Keep `service.type: ClusterIP`, because another type also makes the Admin API reachable from outside the cluster. | Yes      | Yes     |
| `adminSecretRef` | [`SecretKeyRef`](#secretkeyref-adminsecretref)               | Reference to the `Opaque` Secret holding the Admin API bearer token: the Secret set as `admin.existingSecret.name` of the chart.                                                                                    | Yes      | Yes     |
| `layout`         | [`GarageClusterLayoutSpec`](#garageclusterlayoutspec-layout) | The role a storage node gets in the cluster layout, see [Scope](#scope).                                                                                                                                            | Yes      | Yes     |

### SecretKeyRef (`adminSecretRef`)

| Field       | Type     | Description                                                                            | Required |
|-------------|----------|----------------------------------------------------------------------------------------|----------|
| `namespace` | `string` | Namespace of the Secret. If not specified, uses the namespace of the `GarageCluster`.  | No       |
| `name`      | `string` | Name of the Secret.                                                                    | Yes      |
| `key`       | `string` | Key within the Secret. The chart reads the token from `GARAGE_ADMIN_TOKEN` by default. | Yes      |

### GarageClusterLayoutSpec (`layout`)

| Field      | Type            | Description                                                                                                                                                                                               | Required |
|------------|-----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| `zone`     | `string`        | Zone the node is placed in. With `replication_factor = 1` this is effectively a label.                                                                                                                    | Yes      |
| `capacity` | `string`        | Storage capacity to advertise, as a Kubernetes quantity such as `20Gi`. Should match the size of the chart's data volume. Garage uses it to weight partition distribution; it is not enforced as a limit. | Yes      |
| `tags`     | `array[string]` | Tags to attach to the node. Informational; order is not significant.                                                                                                                                      | No       |

A change to a `Mutable` field is applied at the next reconcile. A change to `layout` applies a new layout version. On a single node no data moves. A role that you change by hand with the Garage CLI is reset at the next reconcile, at most 10 minutes later.

Note that the CRD schema carries no defaults — every required field has to be spelled out in the manifest.

The token needs access to these Admin API endpoints: `GetClusterStatus`, `GetClusterHealth`, `GetClusterLayout`, `UpdateClusterLayout` and `ApplyClusterLayout`. The token of the Helm chart has access to all endpoints. A Garage admin token with a smaller scope must list the endpoints that it allows.

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

`ERROR` has three causes:

- The Secret, the token or the endpoint is wrong, or Garage cannot be reached. The operator tries again every 60 seconds.
- Another `GarageCluster` already manages the node. The operator tries again every 60 seconds, so this one takes over when the other one is deleted.
- Garage reports more than one node. The operator does not try again before the next resync after 10 minutes, or a change of the spec.

`health`, `storageNodes` and `storageNodesUp` are only set while the phase is `READY`, so another phase does not show an old value.

## How reconciliation works

1. Read `GetClusterStatus`. No nodes, or the node not connected, means Garage is still coming up →
   `PENDING`, retry. A node that another `GarageCluster` already manages → `ERROR`.
2. Compare the node's assigned role against the spec. Tags are compared as a set, and capacity is compared in bytes, so a reordered tag list or `20Gi` versus `21474836480` is not a change.
3. If they match, stop — nothing is written to Garage.
4. Otherwise read `GetClusterLayout` for the authoritative version number, stage the role with
   `UpdateClusterLayout`, then commit it with `ApplyClusterLayout` at version + 1.
5. Read `GetClusterHealth` to fill in the status, and report `READY`.

Because step 2 short-circuits, reconciliation is idempotent: a `GarageCluster` whose layout already matches never creates a new layout version, no matter how often it is reconciled.
