package it.aboutbits.garage.crd.garagecluster;

import it.aboutbits.garage.core.adminapi.dto.NodeAssignedRole;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class GarageClusterServiceTest {
    private final GarageClusterService garageClusterService = new GarageClusterService();

    @Nested
    class DesiredRole {
        @Test
        @DisplayName("The spec should be translated into the role Garage records")
        void spec_isTranslatedToRole() {
            var layoutSpec = new GarageClusterLayoutSpec();

            layoutSpec.setZone("dc1");
            layoutSpec.setCapacity("20Gi");
            layoutSpec.setTags(List.of("ssd"));

            var role = garageClusterService.desiredRole(layoutSpec);

            assertThat(role.zone()).isEqualTo("dc1");
            assertThat(role.capacity()).isEqualTo(21474836480L);
            assertThat(role.tags()).containsExactly("ssd");
        }

        @Test
        @DisplayName("Tags set to an explicit null should mean no tags")
        void nullTags_meanNoTags() {
            var layoutSpec = new GarageClusterLayoutSpec();

            layoutSpec.setZone("dc1");
            layoutSpec.setCapacity("20Gi");
            layoutSpec.setTags(null);

            var role = garageClusterService.desiredRole(layoutSpec);

            assertThat(role.tags()).isEmpty();
        }
    }

    @Nested
    class RolesMatch {
        private final NodeAssignedRole desiredRole = new NodeAssignedRole(
                "default",
                21474836480L,
                List.of("ssd", "fast")
        );

        @Test
        @DisplayName("A node without a role should never match")
        void noRole_doesNotMatch() {
            assertThat(garageClusterService.rolesMatch(null, desiredRole)).isFalse();
        }

        @Test
        @DisplayName("An identical role should match")
        void identicalRole_matches() {
            var currentRole = new NodeAssignedRole(
                    "default",
                    21474836480L,
                    List.of("ssd", "fast")
            );

            assertThat(garageClusterService.rolesMatch(currentRole, desiredRole)).isTrue();
        }

        @Test
        @DisplayName("Tag order should not count as a difference")
        void reorderedTags_match() {
            var currentRole = new NodeAssignedRole(
                    "default",
                    21474836480L,
                    List.of("fast", "ssd")
            );

            assertThat(garageClusterService.rolesMatch(currentRole, desiredRole)).isTrue();
        }

        @Test
        @DisplayName("A different zone should count as a difference")
        void differentZone_doesNotMatch() {
            var currentRole = new NodeAssignedRole(
                    "dc2",
                    21474836480L,
                    List.of("ssd", "fast")
            );

            assertThat(garageClusterService.rolesMatch(currentRole, desiredRole)).isFalse();
        }

        @Test
        @DisplayName("A different capacity should count as a difference")
        void differentCapacity_doesNotMatch() {
            var currentRole = new NodeAssignedRole(
                    "default",
                    1073741824L,
                    List.of("ssd", "fast")
            );

            assertThat(garageClusterService.rolesMatch(currentRole, desiredRole)).isFalse();
        }

        @Test
        @DisplayName("A gateway node should not match a node with a capacity")
        void gatewayNode_doesNotMatch() {
            var currentRole = new NodeAssignedRole(
                    "default",
                    null,
                    List.of("ssd", "fast")
            );

            assertThat(garageClusterService.rolesMatch(currentRole, desiredRole)).isFalse();
        }

        @Test
        @DisplayName("Different tags should count as a difference")
        void differentTags_doNotMatch() {
            var currentRole = new NodeAssignedRole(
                    "default",
                    21474836480L,
                    List.of("ssd")
            );

            assertThat(garageClusterService.rolesMatch(currentRole, desiredRole)).isFalse();
        }
    }
}
