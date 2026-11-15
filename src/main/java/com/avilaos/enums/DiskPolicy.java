package com.avilaos.enums;

// Políticas de planificación del disco. DiskSchedulerFactory las traduce a su implementación.
public enum DiskPolicy {
    FIFO,     // en orden de llegada
    SSTF,     // la solicitud más cercana al cabezal
    SCAN,     // barre hasta el extremo del disco y regresa (ascensor)
    C_SCAN    // barre hacia arriba; al llegar al extremo salta al bloque 0 sin atender en el regreso
}
