package it.aboutbits.garage.core;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import it.aboutbits.garage.core.adminapi.GarageService;
import it.aboutbits.garage.crd.s3connection.S3Connection;
import it.aboutbits.garage.crd.s3connection.S3ConnectionReconciler;
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
    private final S3ConnectionReconciler reconciler = new S3ConnectionReconciler(mock(GarageService.class));

    private static S3Connection s3Connection(
            String name,
            long generation
    ) {
        var s3Connection = new S3Connection();

        s3Connection.setMetadata(new ObjectMetaBuilder()
                .withNamespace(NAMESPACE)
                .withName(name)
                .withGeneration(generation)
                .build()
        );

        return s3Connection;
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
    class GetReferencedS3Connection {
        /// The connectionRef has no namespace, so the connection is looked up in the namespace of the resource.
        private final S3Connection resource = s3Connection("acme-resource", 1);

        private final ResourceRef connectionRef = connectionRef();

        private static ResourceRef connectionRef() {
            var connectionRef = new ResourceRef();

            connectionRef.setName(CONNECTION_NAME);

            return connectionRef;
        }

        @SuppressWarnings("unchecked")
        private void givenConnection(@Nullable S3Connection s3Connection) {
            MixedOperation<S3Connection, KubernetesResourceList<S3Connection>, Resource<S3Connection>> operation =
                    mock(MixedOperation.class);
            NonNamespaceOperation<S3Connection, KubernetesResourceList<S3Connection>, Resource<S3Connection>> namespaceOperation =
                    mock(NonNamespaceOperation.class);
            Resource<S3Connection> connectionResource = mock(Resource.class);

            when(kubernetesClient.resources(S3Connection.class)).thenReturn(operation);
            when(operation.inNamespace(NAMESPACE)).thenReturn(namespaceOperation);
            when(namespaceOperation.withName(CONNECTION_NAME)).thenReturn(connectionResource);
            when(connectionResource.get()).thenReturn(s3Connection);
        }

        @Test
        @DisplayName("A missing connection should not be returned")
        void missingConnection_isEmpty() {
            // given
            givenConnection(null);

            // when
            var result = reconciler.getReferencedS3Connection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("A connection that has not been probed yet should not be returned")
        void connectionWithoutStatus_isEmpty() {
            // given
            givenConnection(s3Connection(CONNECTION_NAME, 1));

            // when
            var result = reconciler.getReferencedS3Connection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("A connection that is not ready should not be returned")
        void pendingConnection_isEmpty() {
            // given
            var s3Connection = s3Connection(CONNECTION_NAME, 1);

            s3Connection.setStatus(status(CRPhase.PENDING, 1));

            givenConnection(s3Connection);

            // when
            var result = reconciler.getReferencedS3Connection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("A connection that is ready for an older generation should not be returned")
        void readyForOlderGeneration_isEmpty() {
            // given
            var s3Connection = s3Connection(CONNECTION_NAME, 2);

            s3Connection.setStatus(status(CRPhase.READY, 1));

            givenConnection(s3Connection);

            // when
            var result = reconciler.getReferencedS3Connection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("A connection that is ready for the current generation should be returned")
        void readyForCurrentGeneration_isReturned() {
            // given
            var s3Connection = s3Connection(CONNECTION_NAME, 2);

            s3Connection.setStatus(status(CRPhase.READY, 2));

            givenConnection(s3Connection);

            // when
            var result = reconciler.getReferencedS3Connection(kubernetesClient, resource, connectionRef);

            // then
            assertThat(result).containsSame(s3Connection);
        }
    }
}
