package it.aboutbits.garage.core;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import it.aboutbits.garage.core.adminapi.GarageService;
import it.aboutbits.garage.crd.garageclusterconnection.GarageClusterConnection;
import it.aboutbits.garage.crd.garageclusterconnection.GarageClusterConnectionReconciler;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@NullMarked
class BaseReconcilerTest {
    private static final String NAMESPACE = "acme";
    private static final String CONNECTION_NAME = "acme-connection";

    private final KubernetesClient kubernetesClient = mock(KubernetesClient.class);

    // Any concrete reconciler, since the method under test lives in the base class.
    private final GarageClusterConnectionReconciler reconciler = new GarageClusterConnectionReconciler(mock(GarageService.class));

    private static GarageClusterConnection garageClusterConnection(
            String name,
            long generation
    ) {
        var garageClusterConnection = new GarageClusterConnection();

        garageClusterConnection.setMetadata(new ObjectMetaBuilder()
                .withNamespace(NAMESPACE)
                .withName(name)
                .withGeneration(generation)
                .build()
        );

        return garageClusterConnection;
    }

    private static CRStatus status(
            CRPhase phase,
            long observedGeneration
    ) {
        var status = new CRStatus();

        status.updatePhase(phase)
                .setObservedGeneration(observedGeneration);

        return status;
    }

    @Nested
    class GetReferencedGarageClusterConnection {
        /// The connectionRef has no namespace, so the connection is looked up in the namespace of the resource.
        private final GarageClusterConnection resource = garageClusterConnection("acme-resource", 1);

        private final ResourceRef connectionRef = connectionRef();

        private static ResourceRef connectionRef() {
            var connectionRef = new ResourceRef();

            connectionRef.setName(CONNECTION_NAME);

            return connectionRef;
        }

        @SuppressWarnings("unchecked")
        private void givenConnection(@Nullable GarageClusterConnection garageClusterConnection) {
            MixedOperation<GarageClusterConnection, KubernetesResourceList<GarageClusterConnection>, Resource<GarageClusterConnection>> operation =
                    mock(MixedOperation.class);
            NonNamespaceOperation<GarageClusterConnection, KubernetesResourceList<GarageClusterConnection>, Resource<GarageClusterConnection>> namespaceOperation =
                    mock(NonNamespaceOperation.class);
            Resource<GarageClusterConnection> connectionResource = mock(Resource.class);

            when(kubernetesClient.resources(GarageClusterConnection.class)).thenReturn(operation);
            when(operation.inNamespace(NAMESPACE)).thenReturn(namespaceOperation);
            when(namespaceOperation.withName(CONNECTION_NAME)).thenReturn(connectionResource);
            when(connectionResource.get()).thenReturn(garageClusterConnection);
        }

        @Test
        @DisplayName("A missing connection should not be returned")
        void missingConnection_isEmpty() {
            // given
            givenConnection(null);

            // when
            var result = reconciler.getReferencedGarageClusterConnection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("A connection that has not been probed yet should not be returned")
        void connectionWithoutStatus_isEmpty() {
            // given
            givenConnection(garageClusterConnection(CONNECTION_NAME, 1));

            // when
            var result = reconciler.getReferencedGarageClusterConnection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("A connection that is not ready should not be returned")
        void pendingConnection_isEmpty() {
            // given
            var garageClusterConnection = garageClusterConnection(CONNECTION_NAME, 1);

            garageClusterConnection.setStatus(status(CRPhase.PENDING, 1));

            givenConnection(garageClusterConnection);

            // when
            var result = reconciler.getReferencedGarageClusterConnection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("A connection that is ready for an older generation should not be returned")
        void readyForOlderGeneration_isEmpty() {
            // given
            var garageClusterConnection = garageClusterConnection(CONNECTION_NAME, 2);

            garageClusterConnection.setStatus(status(CRPhase.READY, 1));

            givenConnection(garageClusterConnection);

            // when
            var result = reconciler.getReferencedGarageClusterConnection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("A connection that is ready for the current generation should be returned")
        void readyForCurrentGeneration_isReturned() {
            // given
            var garageClusterConnection = garageClusterConnection(CONNECTION_NAME, 2);

            garageClusterConnection.setStatus(status(CRPhase.READY, 2));

            givenConnection(garageClusterConnection);

            // when
            var result = reconciler.getReferencedGarageClusterConnection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).containsSame(garageClusterConnection);
        }
    }
}
