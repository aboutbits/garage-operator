package it.aboutbits.garage.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface Named {
    @JsonIgnore
    String getName();
}
