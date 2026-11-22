package com.avilaos.metrics;

import com.avilaos.disk.DiskStatus;

// Métricas del disco ya calculadas (las que pide el enunciado: desplazamiento total del cabezal,
// espera promedio en la cola y solicitudes atendidas por política). NaN = no aplica todavía.
public record DiskMetrics(
        int totalDisplacement,
        int served,
        double averageWait,
        int busyCycles,
        int idleCycles,
        double utilization,
        int[] servedPerPolicy) {

    // Todo sale de DiskStatus: el disco ya lleva los contadores, aquí solo se derivan los porcentajes.
    public static DiskMetrics from(DiskStatus s) {
        int total = s.busyCycles() + s.idleCycles();
        double utilization = total == 0 ? Double.NaN : s.busyCycles() * 100.0 / total;
        return new DiskMetrics(s.totalDisplacement(), s.served(), s.averageWait(),
                s.busyCycles(), s.idleCycles(), utilization, s.servedPerPolicy());
    }
}
