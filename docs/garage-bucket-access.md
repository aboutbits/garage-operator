# GarageBucketAccess

A `GarageBucketAccess` grants a [`GarageAccessKey`](garage-access-key.md) permissions on a [`GarageBucket`](garage-bucket.md). It is what makes an access key useful: on its own a key can do nothing.

This is Garage's native permission model — per key, per bucket, three flags. Garage has no IAM and no bucket policies, so there is nothing more expressive to model on it.

## Example

```yaml
apiVersion: garage.aboutbits.it/v1
kind: GarageBucketAccess
metadata:
  namespace: acme
  name: acme-app-uploads
spec:
  bucketRef:
    name: acme-uploads
  accessKeyRef:
    name: acme-app
  permissions:
    - read
    - write
```

## Spec

| Field          | Type                                                 | Description                                                                                                    | Required | Mutable |
|----------------|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|----------|---------|
| `bucketRef`    | [`ResourceRef`](#resourceref-bucketref-accesskeyref) | Reference to the `GarageBucket` resource.                                                                      | Yes      | No      |
| `accessKeyRef` | [`ResourceRef`](#resourceref-bucketref-accesskeyref) | Reference to the `GarageAccessKey` resource. It must live on the same `GarageClusterConnection` as the bucket. | Yes      | No      |
| `permissions`  | `array[string]`                                      | At least one of `read`, `write`, `owner`, see [Permissions](#permissions).                                     | Yes      | Yes     |

### ResourceRef (`bucketRef`, `accessKeyRef`)

| Field       | Type     | Description                                                                                  | Required |
|-------------|----------|----------------------------------------------------------------------------------------------|----------|
| `namespace` | `string` | Namespace of the resource. If not specified, uses the namespace of the `GarageBucketAccess`. | No       |
| `name`      | `string` | Name of the resource.                                                                        | Yes      |

A change to a `Mutable` field is applied at the next reconcile.

The references point at the **Kubernetes resources**, not at backend names, so a grant waits for both to be `READY` and needs no knowledge of backend identifiers.

Both references are immutable: re-pointing an existing grant would leave the permissions on the original bucket or key in place with nothing tracking them. Delete and recreate the `GarageBucketAccess`
instead.

What a reference resolves to can still change: a `GarageBucket` or `GarageAccessKey` deleted and recreated under the same name may stand for a different bucket or key on the backend. The grant follows it — the previous grant, as recorded in the status, is revoked first, then the new one is made.

Only one `GarageBucketAccess` grants a `GarageAccessKey` permissions on a given bucket: of all `GarageBucketAccess`es for the same `GarageBucket` and `GarageAccessKey` resources, in any namespace, the one that already holds the grant, otherwise the oldest one. Every other one reports `ERROR` and changes nothing, because two complete lists would overwrite each other at every reconcile. This also holds for two that are applied together. Deleting a refused one leaves the grant alone, and when the granting one is deleted, the next oldest takes over at its next retry.

The admin token of the `GarageClusterConnection` needs access to the Admin API endpoints `ListBuckets`, `GetBucketInfo`, `AllowBucketKey` and `DenyBucketKey`, see [`GarageClusterConnection`](garage-cluster-connection.md#spec).

### Permissions

| Permission | Allows                                                                                                                      |
|------------|-----------------------------------------------------------------------------------------------------------------------------|
| `read`     | Listing and downloading objects.                                                                                            |
| `write`    | Uploading, overwriting and deleting objects.                                                                                |
| `owner`    | Changing the bucket's own configuration, such as website access or CORS rules. Grants neither `read` nor `write` by itself. |

The list is the **complete desired state**. Removing `write` from the list revokes it on the backend; it is not left in place. An empty list is rejected by the API server — delete the
`GarageBucketAccess` to revoke all access.

## Status

| Field                                    | Description                                          |
|------------------------------------------|------------------------------------------------------|
| `phase`                                  | `PENDING`, `READY`, `ERROR`, `DELETING`              |
| `bucketId` / `accessKeyId`               | Backend identifiers the grant was made with.         |
| `connectionNamespace` / `connectionName` | The `GarageClusterConnection` the grant was made on. |
| `message`                                | Human-readable detail about the current state.       |

The backend identifiers are recorded so the grant can still be revoked after the `GarageBucket` or
`GarageAccessKey` resource is gone — they are routinely deleted first.

`ERROR` means that the last reconciliation failed, and `message` has the reason: for example the backend cannot be reached, another `GarageBucketAccess` already grants the same pair, or the `GarageBucket` and the `GarageAccessKey` live on different `GarageClusterConnection`s. The operator tries again every 60 seconds. For different `GarageClusterConnection`s, it tries again only when the `GarageBucket` or the `GarageAccessKey` changes, or at the resync after 10 minutes.

## Dependencies

A `GarageBucketAccess` stays `PENDING` until its `GarageBucket`, its `GarageAccessKey` and their `GarageClusterConnection` are all `READY`. It does not poll for that: the operator watches `GarageBucket` and `GarageAccessKey` resources and reconciles a waiting grant **as soon as** the one it waits on changes. A 60 second retry remains as a safety net only.

The `GarageBucket` and the `GarageAccessKey` must live on the **same `GarageClusterConnection`**. A grant between a bucket on one backend and a key on another is meaningless, so it is reported as `ERROR` rather than retried; changing either resource triggers a new attempt. A grant made earlier, before one of the two was recreated on another `GarageClusterConnection`, is revoked first, so the key does not keep access to the old bucket.

References are resolved relative to the resource that holds them: a `GarageBucket`'s `connectionRef`
without a namespace means the `GarageBucket`'s namespace, not the grant's.

## Deletion

Deleting a `GarageBucketAccess` revokes every permission the key holds on the bucket.

If the key or the bucket is already gone — deleting either one revokes its grants on the backend anyway — deletion completes without error. If nothing was ever granted, because the resource never left `PENDING`, deletion completes immediately.

If the `GarageClusterConnection` the grant was made on is missing or not `READY`, the resource stays until it is back, and is retried every 60 seconds — except while the whole namespace is being deleted, where waiting for a connection that is being deleted too would block the namespace forever. The resource is then released without revoking the grant.

## Using the credentials

The `GarageAccessKey`'s Secret holds `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`. Garage addresses buckets by path, and its region is whatever `s3_region` is set to in `garage.toml` (`garage` in the AboutBits Helm chart). With the AWS SDK for Java v2:

```java
S3Client.builder()
        .endpointOverride(URI.create("http://acme-garage.acme.svc:3900"))
        .region(Region.of("garage"))
        .forcePathStyle(true)
        // See below.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
        ))
        .build();
```

> **AWS SDK 2.30 and newer:** since early 2025 the SDKs send a CRC32 checksum with every upload
> by default. Garage supports that in general — but with the **Java SDK over plain HTTP**, uploads
> use *signed* chunks with a checksum trailer, and Garage (verified on v2.3.0 and v2.4.1) rejects
> exactly that combination with `400 Invalid payload signature`. Reads and listings keep working,
> which makes it look like a credentials problem when it is not.
>
> Setting `requestChecksumCalculation` and `responseChecksumValidation` to `WHEN_REQUIRED` avoids
> the trailer and fixes it. The setting is safe against any S3 backend, AWS included. Other SDKs
> have equivalents, typically the `AWS_REQUEST_CHECKSUM_CALCULATION=when_required` and
> `AWS_RESPONSE_CHECKSUM_VALIDATION=when_required` environment variables.
>
> The cause is a bug in how Garage verifies the signature of that trailer: it uses the wrong
> algorithm line and chains from the wrong previous signature. Over HTTPS the SDK sends *unsigned*
> chunks with a trailer instead. That path does not have the bug and is covered by Garage's own
> test suite, though it was not re-tested here. The AWS CLI is unaffected, because it hashes the
> whole body up front rather than streaming a trailer. Reported upstream as
> [garage#1549](https://git.deuxfleurs.fr/Deuxfleurs/garage/issues/1549).
