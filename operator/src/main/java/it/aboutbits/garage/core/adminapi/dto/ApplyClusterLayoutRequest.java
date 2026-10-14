package it.aboutbits.garage.core.adminapi.dto;

import org.jspecify.annotations.NullMarked;

/// [#version] is the *new* version: the current one plus one.
@NullMarked
public record ApplyClusterLayoutRequest(
        long version
) {
}
