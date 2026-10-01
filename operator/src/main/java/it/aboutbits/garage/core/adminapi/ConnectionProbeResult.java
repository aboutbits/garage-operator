package it.aboutbits.garage.core.adminapi;

import org.jspecify.annotations.NullMarked;

/// A reachable Garage that is not ready is not an error; an unreachable one throws instead.
@NullMarked
public record ConnectionProbeResult(
        boolean available,
        String detail
) {
    public static ConnectionProbeResult available(String detail) {
        return new ConnectionProbeResult(true, detail);
    }

    public static ConnectionProbeResult unavailable(String detail) {
        return new ConnectionProbeResult(false, detail);
    }
}
