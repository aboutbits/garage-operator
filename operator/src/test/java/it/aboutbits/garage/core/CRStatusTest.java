package it.aboutbits.garage.core;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class CRStatusTest {
    @Nested
    class SetPhase {
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

            status.setPhase(CRPhase.READY);

            assertThat(status.getPhase()).isEqualTo(CRPhase.READY);
            assertThat(status.getLastPhaseTransitionTime()).isNotNull();
        }

        @Test
        @DisplayName("Setting the same phase again should not touch the transition time")
        void samePhase_keepsTransitionTime() {
            var status = new CRStatus();

            status.setPhase(CRPhase.READY);

            var transitionTime = status.getLastPhaseTransitionTime();

            status.setPhase(CRPhase.READY);

            assertThat(status.getLastPhaseTransitionTime()).isEqualTo(transitionTime);
        }

        @Test
        @DisplayName("Setting the phase should be chainable with the remaining status setters")
        void setPhase_isChainable() {
            var status = new CRStatus()
                    .setPhase(CRPhase.ERROR)
                    .setMessage("Something went wrong");

            assertThat(status.getPhase()).isEqualTo(CRPhase.ERROR);
            assertThat(status.getMessage()).isEqualTo("Something went wrong");
        }
    }
}
