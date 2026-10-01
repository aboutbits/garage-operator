# BucketAccess

A `BucketAccess` grants an [`AccessKey`](access-key.md) permissions on a [`Bucket`](bucket.md). It is what makes an access key useful: on its own a key can do nothing.

This is Garage's native permission model — per key, per bucket, three flags. Garage has no IAM and no bucket policies, so there is nothing more expressive to model on it.

## Example

```yaml
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

## Spec

| Field                    | Required | Description                                      |
|--------------------------|----------|--------------------------------------------------|
| `bucketRef.name`         | yes      | The `Bucket` resource. **Immutable.**            |
| `bucketRef.namespace`    | no       | Defaults to the namespace of the `BucketAccess`. |
| `accessKeyRef.name`      | yes      | The `AccessKey` resource. **Immutable.**         |
| `accessKeyRef.namespace` | no       | Defaults to the namespace of the `BucketAccess`. |
| `permissions`            | yes      | At least one of `read`, `write`, `owner`.        |

The references point at the **Kubernetes resources**, not at backend names, so a grant waits for both to be `READY` and needs no knowledge of backend identifiers.

Both references are immutable: re-pointing an existing grant would leave the permissions on the original bucket or key in place with nothing tracking them. Delete and recreate the `BucketAccess`
instead.

What a reference resolves to can still change: a `Bucket` or `AccessKey` deleted and recreated under the same name may stand for a different bucket or key on the backend. The grant follows it — the previous grant, as recorded in the status, is revoked first, then the new one is made.

### Permissions

| Permission | Allows                                                                                                                      |
|------------|-----------------------------------------------------------------------------------------------------------------------------|
| `read`     | Listing and downloading objects.                                                                                            |
| `write`    | Uploading, overwriting and deleting objects.                                                                                |
| `owner`    | Changing the bucket's own configuration, such as website access or CORS rules. Grants neither `read` nor `write` by itself. |

The list is the **complete desired state**. Removing `write` from the list revokes it on the backend; it is not left in place. An empty list is rejected by the API server — delete the
`BucketAccess` to revoke all access.

## Status

| Field                                    | Description                                    |
|------------------------------------------|------------------------------------------------|
| `phase`                                  | `PENDING`, `READY`, `ERROR`, `DELETING`        |
| `bucketId` / `accessKeyId`               | Backend identifiers the grant was made with.   |
| `connectionNamespace` / `connectionName` | The `S3Connection` the grant was made on.      |
| `message`                                | Human-readable detail about the current state. |

The backend identifiers are recorded so the grant can still be revoked after the `Bucket` or
`AccessKey` resource is gone — they are routinely deleted first.

## Dependencies

A `BucketAccess` stays `PENDING` until its `Bucket`, its `AccessKey` and their `S3Connection` are all `READY`. It does not poll for that: the operator watches `Bucket` and `AccessKey` resources and reconciles a waiting grant **as soon as** the one it waits on changes. A 60 second retry remains as a safety net only.

The `Bucket` and the `AccessKey` must live on the **same `S3Connection`**. A grant between a bucket on one backend and a key on another is meaningless, so it is reported as `ERROR` rather than retried; changing either resource triggers a new attempt.

References are resolved relative to the resource that holds them: a `Bucket`'s `connectionRef`
without a namespace means the `Bucket`'s namespace, not the grant's.

## Deletion

Deleting a `BucketAccess` revokes every permission the key holds on the bucket.

If the key or the bucket is already gone — deleting either one revokes its grants on the backend anyway — deletion completes without error. If nothing was ever granted, because the resource never left `PENDING`, deletion completes immediately.

If the `S3Connection` the grant was made on is missing, the resource stays until it is back — except while the whole namespace is being deleted, where waiting for a connection that is being deleted too would block the namespace forever. The resource is then released without revoking the grant.

## Using the credentials

The `AccessKey`'s Secret holds `accessKeyId` and `secretAccessKey`. Garage addresses buckets by path, and its region is whatever `s3_region` is set to in `garage.toml` (`garage` in the AboutBits Helm chart). With the AWS SDK for Java v2:

```java
S3Client.builder()
        .

endpointOverride(URI.create("http://acme-garage.acme.svc:3900"))
        .

region(Region.of("garage"))
        .

forcePathStyle(true)
// See below.
        .

requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .

responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .

credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKeyId, secretAccessKey)
        ))
                .

build();
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
> whole body up front rather than streaming a trailer.
