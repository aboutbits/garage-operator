package it.aboutbits.garage.core;

import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class CRStatusTest {
    @Nested
    class UpdatePhase {
        @Test
        @DisplayName("A new status should start out pending without a transition time")
        void newStatus_isPending() {
            var status = new CRStatus();

            assertThat(status.getPhase()).isEqualTo(CRPhase.PENDING);
            assertThat(status.getLastPhaseTransitionTime()).isNull();
        }

        @Test
        @DisplayName("Changing the phase should stamp the transition time")
        void phaseChange_stampsTransitionTime() {
            var status = new CRStatus();

            status.updatePhase(CRPhase.READY);

            assertThat(status.getPhase()).isEqualTo(CRPhase.READY);
            assertThat(status.getLastPhaseTransitionTime()).isNotNull();
        }

        @Test
        @DisplayName("Setting the same phase again should not touch the transition time")
        void samePhase_keepsTransitionTime() {
            var status = new CRStatus();

            status.updatePhase(CRPhase.READY);

            var transitionTime = status.getLastPhaseTransitionTime();

            status.updatePhase(CRPhase.READY);

            assertThat(status.getLastPhaseTransitionTime()).isEqualTo(transitionTime);
        }

        @Test
        @DisplayName("Updating the phase should be chainable with the remaining status setters")
        void updatePhase_isChainable() {
            var status = new CRStatus()
                    .updatePhase(CRPhase.ERROR)
                    .setMessage("Something went wrong");

            assertThat(status.getPhase()).isEqualTo(CRPhase.ERROR);
            assertThat(status.getMessage()).isEqualTo("Something went wrong");
        }
    }

    @Nested
    class Deserialize {
        @Test
        @DisplayName("Reading a status from the cluster should keep its transition time")
        void statusFromCluster_keepsTransitionTime() {
            // given
            // The API server sends the status keys in alphabetical order.
            var json = """
                    {"lastPhaseTransitionTime":"2026-01-01T00:00:00Z","phase":"READY"}
                    """;

            // when
            var status = new KubernetesSerialization().unmarshal(
                    json,
                    CRStatus.class
            );

            // then
            assertThat(status.getPhase()).isEqualTo(CRPhase.READY);
            assertThat(status.getLastPhaseTransitionTime())
                    .isNotNull()
                    .isAtSameInstantAs(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        }
    }
}
