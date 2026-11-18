package it.aboutbits.garage.core.adminapi.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/// In how many zones Garage places the copies of a partition. On the wire either `"maximum"` or `{"atLeast": n}`.
@NullMarked
public record ZoneRedundancy(
        // `null` means `maximum`.
        @Nullable Integer atLeast
) {
    public static final ZoneRedundancy MAXIMUM = new ZoneRedundancy(null);

    public static ZoneRedundancy atLeastZones(int zones) {
        return new ZoneRedundancy(zones);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static ZoneRedundancy fromJson(JsonNode node) {
        if (node.isTextual() && "maximum".equals(node.asText())) {
            return MAXIMUM;
        }

        var atLeast = node.path("atLeast");

        if (atLeast.canConvertToInt()) {
            return atLeastZones(atLeast.asInt());
        }

        throw new IllegalArgumentException("Unknown zone redundancy [zoneRedundancy=%s]".formatted(node));
    }

    @JsonValue
    public Object toJson() {
        return atLeast != null
                ? Map.of("atLeast", atLeast)
                : "maximum";
    }
}
