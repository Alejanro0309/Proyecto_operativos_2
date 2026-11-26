package com.avilaos.disk;

import com.avilaos.enums.DiskPolicy;

// Crea la política de disco a partir del enum. Para agregar una política: nueva clase que
// implemente DiskSchedulingAlgorithm, un valor en DiskPolicy y un case aquí.
public final class DiskSchedulerFactory {

    private DiskSchedulerFactory() {
    }

    public static DiskSchedulingAlgorithm create(DiskPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("La política de disco es obligatoria");
        }
        switch (policy) {
            case FIFO:
                return new FifoDiskScheduler();
            case SSTF:
                return new SstfDiskScheduler();
            case SCAN:
                return new ScanDiskScheduler();
            case C_SCAN:
                return new CScanDiskScheduler();
            default:
                throw new IllegalArgumentException("Política de disco no soportada: " + policy);
        }
    }
}
