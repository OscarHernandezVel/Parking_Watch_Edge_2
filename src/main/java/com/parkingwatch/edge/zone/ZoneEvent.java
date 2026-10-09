package com.parkingwatch.edge.zone;

import com.parkingwatch.common.domain.ParkingEventType;
import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.domain.MonitoredZone;
import java.time.Instant;

/** Cambio de un vehículo respecto a una zona: entra, supera la tolerancia o sale (RF-9.4). */
public record ZoneEvent(
    ParkingEventType type,
    MonitoredZone zone,
    long trackId,
    VehicleType vehicleType,
    double confidence,
    BoundingBox box,
    Instant enteredAt,
    Instant occurredAt,
    double dwellSeconds) {}
