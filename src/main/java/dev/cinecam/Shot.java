package dev.cinecam;

import java.util.Locale;
import java.util.Optional;

/**
 * The cinematic shots Cinecam can play. Every shot is a function of time and
 * orbits, sweeps, swings or flies around the player.
 */
public enum Shot {
    ORBIT("Orbit", "a slow, perfectly steady circle around the player"),
    SPIRAL("Spiral", "a circle that climbs and sinks while the radius breathes"),
    CRANE("Crane", "a jib-style orbit that descends and pushes in"),
    DOLLY("Dolly", "an orbit with a slow in-and-out push and a gentle roll"),
    FLYBY("Flyby", "a wide low-to-high pass that sweeps past the player"),
    FIGURE8("Figure 8", "a lemniscate that loops in front of and behind the player"),
    TOPDOWN("Top Down", "a high overhead orbit that looks straight down"),
    PENDULUM("Pendulum", "a slow arc that swings past the player and back"),
    VERTIGO("Vertigo", "a dolly-zoom orbit that pushes in as the lens widens");

    private final String displayName;
    private final String description;

    Shot(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Shot next() {
        Shot[] shots = values();
        return shots[(ordinal() + 1) % shots.length];
    }

    public static Optional<Shot> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Shot shot : values()) {
            if (shot.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(shot);
            }
        }
        return Optional.empty();
    }
}
