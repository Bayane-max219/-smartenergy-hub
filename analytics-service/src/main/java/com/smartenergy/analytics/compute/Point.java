package com.smartenergy.analytics.compute;

import java.time.Instant;

/** Une observation de puissance (kW) à un instant donné. */
public record Point(Instant timestamp, double powerKw) {
}
