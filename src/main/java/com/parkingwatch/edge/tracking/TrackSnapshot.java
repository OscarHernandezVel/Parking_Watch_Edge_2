package com.parkingwatch.edge.tracking;

import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;

/** Estado inmutable de una pista en un fotograma. */
public record TrackSnapshot(long trackId, BoundingBox box, VehicleType type, double confidence) {}
