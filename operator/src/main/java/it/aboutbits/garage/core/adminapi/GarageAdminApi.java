package it.aboutbits.garage.core.adminapi;

import it.aboutbits.garage.core.adminapi.dto.ApplyClusterLayoutRequest;
import it.aboutbits.garage.core.adminapi.dto.ApplyClusterLayoutResponse;
import it.aboutbits.garage.core.adminapi.dto.BucketKeyPermChangeRequest;
import it.aboutbits.garage.core.adminapi.dto.CreateBucketRequest;
import it.aboutbits.garage.core.adminapi.dto.GetBucketInfoResponse;
import it.aboutbits.garage.core.adminapi.dto.GetClusterHealthResponse;
import it.aboutbits.garage.core.adminapi.dto.GetClusterLayoutResponse;
import it.aboutbits.garage.core.adminapi.dto.GetClusterStatusResponse;
import it.aboutbits.garage.core.adminapi.dto.GetKeyInfoResponse;
import it.aboutbits.garage.core.adminapi.dto.ListBucketsResponseItem;
import it.aboutbits.garage.core.adminapi.dto.ListKeysResponseItem;
import it.aboutbits.garage.core.adminapi.dto.UpdateBucketRequestBody;
import it.aboutbits.garage.core.adminapi.dto.UpdateClusterLayoutRequest;
import it.aboutbits.garage.core.adminapi.dto.UpdateKeyRequestBody;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/// The subset of the [Garage Admin API v2](https://garagehq.deuxfleurs.fr/documentation/reference-manual/admin-api/)
/// the operator needs. Clients are built per endpoint and token by [GarageAdminClientFactory].
@Path("/v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@NullMarked
public interface GarageAdminApi {
    @GET
    @Path("/GetClusterStatus")
    GetClusterStatusResponse getClusterStatus();

    @GET
    @Path("/GetClusterHealth")
    GetClusterHealthResponse getClusterHealth();

    @GET
    @Path("/GetClusterLayout")
    GetClusterLayoutResponse getClusterLayout();

    /// Staged changes take effect only on [#applyClusterLayout].
    @POST
    @Path("/UpdateClusterLayout")
    GetClusterLayoutResponse updateClusterLayout(UpdateClusterLayoutRequest request);

    @POST
    @Path("/ApplyClusterLayout")
    ApplyClusterLayoutResponse applyClusterLayout(ApplyClusterLayoutRequest request);

    /// Used to resolve buckets, because the API documents no response for an unknown bucket on `GetBucketInfo`.
    @GET
    @Path("/ListBuckets")
    List<ListBucketsResponseItem> listBuckets();

    @GET
    @Path("/GetBucketInfo")
    GetBucketInfoResponse getBucketInfo(@QueryParam("id") String id);

    @POST
    @Path("/CreateBucket")
    GetBucketInfoResponse createBucket(CreateBucketRequest request);

    /// Fields left out of the body are not touched.
    @POST
    @Path("/UpdateBucket")
    GetBucketInfoResponse updateBucket(
            @QueryParam("id") String id,
            UpdateBucketRequestBody request
    );

    /// Answers `409` if the bucket still holds objects.
    @POST
    @Path("/DeleteBucket")
    void deleteBucket(@QueryParam("id") String id);

    @GET
    @Path("/ListKeys")
    List<ListKeysResponseItem> listKeys();

    /// Reveals the secret when `showSecretKey` is set.
    @GET
    @Path("/GetKeyInfo")
    GetKeyInfoResponse getKeyInfo(
            @QueryParam("id") String id,
            @QueryParam("showSecretKey") boolean showSecretKey
    );

    @POST
    @Path("/CreateKey")
    GetKeyInfoResponse createKey(UpdateKeyRequestBody request);

    @POST
    @Path("/UpdateKey")
    GetKeyInfoResponse updateKey(
            @QueryParam("id") String id,
            UpdateKeyRequestBody request
    );

    @POST
    @Path("/DeleteKey")
    void deleteKey(@QueryParam("id") String id);

    /// Unflagged permissions are left untouched.
    @POST
    @Path("/AllowBucketKey")
    GetBucketInfoResponse allowBucketKey(BucketKeyPermChangeRequest request);

    /// Unflagged permissions are left untouched.
    @POST
    @Path("/DenyBucketKey")
    GetBucketInfoResponse denyBucketKey(BucketKeyPermChangeRequest request);
}
