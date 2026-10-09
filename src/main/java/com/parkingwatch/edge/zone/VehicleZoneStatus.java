package com.parkingwatch.edge.zone;

import com.parkingwatch.edge.tracking.TrackSnapshot;

/**
 * Estado de un vehículo en un fotograma: zona en la que está (o null) y segundos inmóvil. Alimenta
 * el recuadro y el contador de tiempo sobre el video en vivo (RF-2.2).
 */
public record VehicleZoneStatus(TrackSnapshot track, Long zoneId, double stationarySeconds) {}
