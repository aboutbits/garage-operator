package it.aboutbits.garage.core.adminapi;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;

/// Adds the `Authorization: Bearer <token>` header expected by the Garage Admin API.
@RequiredArgsConstructor
@NullMarked
public class BearerTokenFilter implements ClientRequestFilter {
    private final String token;

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.getHeaders().putSingle(
                HttpHeaders.AUTHORIZATION,
                "Bearer %s".formatted(token)
        );
    }
}
