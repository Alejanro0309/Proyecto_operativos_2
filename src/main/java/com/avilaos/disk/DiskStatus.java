package com.avilaos.disk;

import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;

// Copia inmutable del estado del disco para la interfaz y las métricas (como BufferStatus).
// 'queue' incluye las solicitudes que ya llegaron pero todavía no entran a competir.
// averageWait es NaN mientras no se haya atendido ninguna solicitud.
public record DiskStatus(
        DiskPolicy policy,
        int headPosition,
        HeadDirection direction,
        int totalBlocks,
        boolean busy,
        DiskRequest current,
        DiskRequest[] queue,
        int totalDisplacement,
        int served,
        double averageWait,
        int[] servedPerPolicy,
        int busyCycles,
        int idleCycles) {
}
