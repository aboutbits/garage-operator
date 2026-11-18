# GarageCluster

A `GarageCluster` bootstraps and converges the **cluster layout** of a Garage installation, of one node or of several.

A Garage node started without `--single-node` knows about itself but has no role in any layout, and in that state it **serves no S3 traffic at all** — every request fails until a layout is assigned and applied. The `GarageCluster` assigns that layout.

## Requirements

The operator does not deploy Garage. It works with any Garage 2.x that it reaches through the
[Admin API v2](https://garagehq.deuxfleurs.fr/documentation/reference-manual/admin-api/), whether that Garage was installed with the [AboutBits Garage Helm chart](https://github.com/aboutbits/helm-garage), the official Garage chart or anything else. That Garage has to meet these requirements:

- **Started without `--single-node`.** With `--single-node`, Garage creates the layout itself, and refuses to start once the layout version is higher than 1. A `GarageCluster` applies a new layout version as soon as its spec differs from the roles of the nodes, so with `--single-node` the next restart of Garage fails for good. The AboutBits chart starts Garage with `--single-node` unless it is installed with `garage.singleNode: false`.
- **On several nodes: the nodes find each other.** Connecting the nodes is the job of whatever deploys Garage, for example with Garage's `kubernetes_discovery`, its `bootstrap_peers` or Consul. The operator assigns roles to the nodes that Garage reports, and connects none.
- **An admin token** in a Secret, see [Spec](#spec).

Without a `GarageCluster`, a single node can keep `--single-node`: Garage creates its own layout, and
[`GarageClusterConnection`](garage-cluster-connection.md), `GarageBucket` and `GarageAccessKey` work the same.

The operator cannot see how Garage was started, so it cannot refuse a `GarageCluster` for a Garage in single-node mode.

Once the layout is applied, a [`GarageClusterConnection`](garage-cluster-connection.md) pointed at the same cluster becomes
`READY`, which is what unblocks buckets and access keys.

## Nodes and roles

Garage keeps one layout per cluster, with a role per node. `spec.layout` is the role that every storage node gets:

- **A connected node without a role gets it.** A node that joins the cluster later, for example after a scale-up, gets it at the next reconcile, at most 10 minutes later.
- **A node with a role is kept at it**, also while it is down. A role that you change by hand with the Garage CLI is reset at the next reconcile.
- **A role is never removed because a node is down.** The operator cannot tell a restarting node from one that is gone, and removing a role moves data. Taking a node out is always explicit, see [Taking a node out](#taking-a-node-out).
- **A node without a role that is down, or that is still handing its data over, gets none.**

`spec.minNodes` holds back every change of the layout until at least that many nodes are connected. Set it to the number of nodes, so that the first layout covers all of them at once: otherwise every node that connects later starts a layout version and a rebalance of its own.

Before a change is applied, Garage computes the new layout as a preview. If Garage rejects it — for example because there are fewer storage nodes than the replication factor, or fewer zones than `spec.zoneRedundancy` asks for — the operator applies nothing, discards what it staged and reports `ERROR` with the reason from Garage.

The operator owns the layout. Changes that you stage by hand with the Garage CLI, and do not apply, are applied together with the next change of the operator, or discarded with it if Garage rejects it.

### Taking a node out

1. Add the id of the node to `spec.removedNodes`. The ids are in `status.nodes`. The operator removes the role, and Garage moves the data of the node to the others; meanwhile the node shows `draining: true` in `status.nodes`, which the operator refreshes every 15 seconds until the handover is done. The handover can take minutes even with little data: the removed node has to finish a full sync first.
2. Once `draining` is `false`, shut the node down.

Keep the id in the list as long as the node may connect again: a connected node without a role gets `spec.layout` otherwise. A role that you remove by hand is assigned again for the same reason.

A node that is gone for good, for example one that lost its metadata and started again under a new id, is taken out the same way. Its data cannot be handed over, so Garage keeps the old layout version active until it is told to skip the dead node, with `garage layout skip-dead-nodes`.

## The admin endpoint

Any node answers the Admin API for the whole cluster, so `adminEndpoint` may be the address of one node or of a Service in front of all of them.

A node that is not connected to the others sees only itself. For example, a node that lost its metadata starts again under a new id, alone, without a layout. The operator recognizes such a node by a layout version older than `status.layoutVersion`, or than the version another node reported in the same reconcile, reports `PENDING` and leaves the layout alone. Behind a Service, the next request may reach a connected node. A change that is staged on one node and applied on another is safe too: `ApplyClusterLayout` names the next version, which a node that is behind rejects.

On a Garage of a single node, this means that a node that lost its metadata keeps the `GarageCluster` in `PENDING`. Delete and recreate the `GarageCluster` to assign the new node a layout.

## Scope

Only one `GarageCluster` manages a Garage. A second `GarageCluster` for the same Garage, in any namespace, reports `ERROR` and leaves the layout alone. Otherwise two resources with different layouts would apply their roles in turn, a new layout version at every reconcile. The Garage is recognized by the ids of its nodes, so two different `adminEndpoint` values, even of two different nodes, count as the same Garage. When the first `GarageCluster` is deleted, the second one takes over at its next retry.

Deleting a `GarageCluster` removes the operator's record of the cluster. It does **not** tear the storage cluster down: removing the roles would move data and, on a single-node cluster, discard it. There is therefore no reclaim policy on this resource.

Every storage node gets the same role. A different zone or capacity per node, and gateway nodes without capacity, are not supported yet.

## Example

A single node:

```yaml
apiVersion: garage.aboutbits.it/v1
kind: GarageCluster
metadata:
  namespace: acme
  name: acme
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

Three nodes, one of them being taken out:

```yaml
apiVersion: garage.aboutbits.it/v1
kind: GarageCluster
metadata:
  namespace: acme
  name: acme
spec:
  adminEndpoint: http://acme-garage.acme.svc:3903
  adminSecretRef:
    name: acme-garage
    key: admin_token
  layout:
    zone: default
    capacity: 100Gi
  minNodes: 3
  zoneRedundancy: maximum
  removedNodes:
    - 5f2a0c9d4b7e8a1c3d6f9b2e5a8c1d4f7b0e3a6c9d2f5b8e1a4c7d0f3b6e9a2c
```

## Spec

| Field            | Type                                                         | Description                                                                                                                                                                                                                                   | Required | Mutable |
|------------------|--------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|---------|
| `adminEndpoint`  | `string`                                                     | Base URL of the Garage Admin API, port 3903, of one node or of a Service in front of all nodes. Keep that Service internal to the cluster, such as a `ClusterIP` Service, so that the Admin API is not reachable from outside.                 | Yes      | Yes     |
| `adminSecretRef` | [`SecretKeyRef`](#secretkeyref-adminsecretref)               | Reference to the `Opaque` Secret holding the Admin API bearer token.                                                                                                                                                                          | Yes      | Yes     |
| `layout`         | [`GarageClusterLayoutSpec`](#garageclusterlayoutspec-layout) | The role every storage node gets in the cluster layout, see [Nodes and roles](#nodes-and-roles).                                                                                                                                              | Yes      | Yes     |
| `minNodes`       | `integer`                                                    | The layout is left alone while fewer nodes are connected. At least `1`, which is also the default.                                                                                                                                            | No       | Yes     |
| `zoneRedundancy` | `integer` or `string`                                        | In how many zones Garage places the copies of a partition: `maximum`, or a number of zones of at least `1`. Not set leaves the setting of Garage as it is; Garage starts with `maximum`.                                                       | No       | Yes     |
| `removedNodes`   | `array[string]`                                              | Full ids (64 hexadecimal characters) of nodes to take out of the layout, see [Taking a node out](#taking-a-node-out). Ids rather than hostnames, because a node that replaces a dead one may reuse its hostname.                               | No       | Yes     |

### SecretKeyRef (`adminSecretRef`)

| Field       | Type     | Description                                                                                                 | Required |
|-------------|----------|-------------------------------------------------------------------------------------------------------------|----------|
| `namespace` | `string` | Namespace of the Secret. If not specified, uses the namespace of the `GarageCluster`.                       | No       |
| `name`      | `string` | Name of the Secret.                                                                                         | Yes      |
| `key`       | `string` | Key within the Secret. The AboutBits chart creates a Secret named after the release, with the key `admin_token`. | Yes      |

### GarageClusterLayoutSpec (`layout`)

| Field      | Type            | Description                                                                                                                                                                                                       | Required |
|------------|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| `zone`     | `string`        | Zone the nodes are placed in. Garage spreads the copies of a partition over zones, see `zoneRedundancy`; with all nodes in one zone it is effectively a label.                                                     | Yes      |
| `capacity` | `string`        | Storage capacity to advertise per node, as a Kubernetes quantity such as `20Gi`. Should match the size of a node's data volume. Garage uses it to weight partition distribution; it is not enforced as a limit.   | Yes      |
| `tags`     | `array[string]` | Tags to attach to the nodes. Informational; order is not significant.                                                                                                                                             | No       |

A change to a `Mutable` field is applied at the next reconcile. A change to `layout`, `zoneRedundancy` or `removedNodes` applies a new layout version. On a single node no data moves; on several nodes Garage rebalances the data.

Note that the CRD schema carries no defaults — every required field has to be spelled out in the manifest.

The token needs access to these Admin API endpoints: `GetClusterStatus`, `GetClusterHealth`, `GetClusterLayout`, `UpdateClusterLayout`, `PreviewClusterLayoutChanges`, `ApplyClusterLayout` and `RevertClusterLayout`. A Garage admin token with a smaller scope must list the endpoints that it allows.

## Status

| Field                             | Description                                                                                                                                         |
|-----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `phase`                           | `PENDING`, `READY`, `ERROR`                                                                                                                         |
| `layoutVersion`                   | Version number of the applied cluster layout. `0` means none has been applied.                                                                      |
| `nodes`                           | Every node that Garage reports: `id`, `hostname`, `up`, `draining`, and the `zone` and `capacity` (in bytes) of its role, which are empty without one. |
| `health`                          | Cluster health as reported by Garage: `healthy`, `degraded` or `unavailable`.                                                                       |
| `storageNodes` / `storageNodesUp` | Storage nodes in the layout, and how many are connected.                                                                                            |
| `message`                         | Human-readable detail about the current state.                                                                                                      |

`nodeId`, the single node of a `GarageCluster` from an earlier version of the operator, is cleared at the first reconcile.

`PENDING` is the normal state while Garage is being installed, and is retried every 15 seconds. It has two causes:

- Fewer nodes are connected than `spec.minNodes`, or Garage reports no nodes yet.
- The node behind the admin endpoint reports an older layout version than known, see [The admin endpoint](#the-admin-endpoint).

`ERROR` has three causes, and is retried every 60 seconds:

- The Secret, the token or the endpoint is wrong, or Garage cannot be reached.
- Another `GarageCluster` already manages the Garage. This one takes over when the other one is deleted.
- Garage rejected the layout. A node that connects later can make it valid.

`READY` is checked again every 15 seconds while a node is draining, and otherwise at the resync after 10 minutes.

`health`, `storageNodes` and `storageNodesUp` are only set while the phase is `READY`, so another phase does not show an old value.

## How reconciliation works

1. Read `GetClusterStatus`. Nodes that another `GarageCluster` already manages → `ERROR`. An older layout version than `status.layoutVersion`, or fewer connected nodes than `spec.minNodes` → `PENDING`, retry.
2. Read `GetClusterLayout` for the authoritative version number and the layout parameters. An older version than known → `PENDING`, retry. Compare the role of every node against `spec.layout` and `spec.removedNodes`, and the parameters against `spec.zoneRedundancy`. Tags are compared as a set, and capacity is compared in bytes, so a reordered tag list or `20Gi` versus `21474836480` is not a change.
3. If everything matches, stop — nothing is written to Garage.
4. Otherwise stage all changes with one `UpdateClusterLayout`, and check them with `PreviewClusterLayoutChanges`. If Garage rejects them, discard them with `RevertClusterLayout` → `ERROR`.
5. Commit the changes with `ApplyClusterLayout` at version + 1.
6. Read `GetClusterHealth` to fill in the status, and report `READY`.

Because step 3 short-circuits, reconciliation is idempotent: a `GarageCluster` whose layout already matches never creates a new layout version, no matter how often it is reconciled.
