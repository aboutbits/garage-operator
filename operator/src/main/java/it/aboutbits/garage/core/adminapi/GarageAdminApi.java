package it.aboutbits.garage.core.adminapi;

import it.aboutbits.garage.core.adminapi.dto.ApplyClusterLayoutRequest;
import it.aboutbits.garage.core.adminapi.dto.ApplyClusterLayoutResponse;
import it.aboutbits.garage.core.adminapi.dto.GetClusterHealthResponse;
import it.aboutbits.garage.core.adminapi.dto.GetClusterLayoutResponse;
import it.aboutbits.garage.core.adminapi.dto.GetClusterStatusResponse;
import it.aboutbits.garage.core.adminapi.dto.UpdateClusterLayoutRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.NullMarked;

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
}
