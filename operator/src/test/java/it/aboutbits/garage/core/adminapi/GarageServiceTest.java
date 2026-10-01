package it.aboutbits.garage.core.adminapi;

import it.aboutbits.garage.core.adminapi.dto.GetClusterHealthResponse;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// The cluster states a shared Dev Service cannot be driven into reliably — an unconfigured
/// cluster, in particular, since other tests apply a layout to it — are pinned down here instead.
@NullMarked
class GarageServiceTest {
    private final GarageAdminApi garageAdminApi = mock(GarageAdminApi.class);
    private final GarageAdminClientFactory garageAdminClientFactory = mock(GarageAdminClientFactory.class);

    private final GarageService garageService = new GarageService(garageAdminClientFactory);

    private void givenClusterHealth(String status) {
        when(garageAdminApi.getClusterHealth()).thenReturn(new GetClusterHealthResponse(
                status,
                1,
                1,
                1,
                1,
                256,
                256,
                256
        ));

        when(garageAdminClientFactory.create(any(S3Connection.class))).thenReturn(garageAdminApi);
    }

    @Nested
    class Probe {
        @ParameterizedTest
        @ValueSource(strings = {"healthy", "degraded"})
        @DisplayName("A cluster that can serve requests should be available")
        void servingCluster_isAvailable(String status) {
            givenClusterHealth(status);

            var result = garageService.probe(new S3Connection());

            assertThat(result.available()).isTrue();
            assertThat(result.detail()).contains(status);
        }

        @Test
        @DisplayName("An unavailable cluster should not be available, and should point at the layout")
        void unavailableCluster_isNotAvailable() {
            givenClusterHealth("unavailable");

            var result = garageService.probe(new S3Connection());

            assertThat(result.available()).isFalse();
            assertThat(result.detail()).contains("GarageCluster");
        }
    }
}
