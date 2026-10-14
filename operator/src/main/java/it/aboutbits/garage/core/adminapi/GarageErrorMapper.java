package it.aboutbits.garage.core.adminapi;

import it.aboutbits.garage.core.adminapi.dto.ErrorResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The default mapper keeps only the reason phrase, but Garage explains the error in a JSON body with `code` and
/// `message`. Still a [WebApplicationException], so that callers can handle a status such as `409`.
@NullMarked
class GarageErrorMapper implements ResponseExceptionMapper<WebApplicationException> {
    @Override
    public WebApplicationException toThrowable(Response response) {
        // Mappers run on the event loop, and reading an entity that is not buffered blocks.
        response.bufferEntity();

        var status = response.getStatus();
        var error = readError(response);

        // Not a Garage error body, for example from a proxy in front of Garage.
        if (error == null) {
            return new WebApplicationException(
                    "The Garage Admin API request failed [status=%d, reason=%s]".formatted(
                            status,
                            response.getStatusInfo().getReasonPhrase()
                    ),
                    response
            );
        }

        return new WebApplicationException(
                "The Garage Admin API request failed [status=%d, code=%s, message=%s]".formatted(
                        status,
                        error.code(),
                        error.message()
                ),
                response
        );
    }

    private @Nullable ErrorResponse readError(Response response) {
        if (!response.hasEntity()) {
            return null;
        }

        try {
            return response.readEntity(ErrorResponse.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
