package com.avilaos.metrics;

import com.avilaos.hardware.ClockListener;
import com.avilaos.hardware.Disk;
import com.avilaos.disk.DiskStatus;
import java.util.concurrent.Semaphore;

// Guarda, ciclo a ciclo, el desplazamiento acumulado del cabezal y la política activa, para la gráfica
// que pide el enunciado (desplazamiento acumulado contra el tiempo, para comparar políticas).
// Se registra como observador de fin de ciclo (junto con MetricsCollector, ver CompositeClockListener):
// el reloj lo llama cuando todos los componentes terminaron el ciclo, así el disco está quieto.
// Historial circular propio (sin colecciones) protegido con un Semaphore: escribe el hilo del reloj
// y lee el de Swing.
public class DiskMetricsCollector implements ClockListener {

    public static final int HISTORY_SIZE = 2000;

    // Copia del historial, de la muestra más vieja a la más nueva. 'policy' guarda el ordinal de DiskPolicy.
    public record DisplacementSnapshot(int oldestCycle, int latestCycle, int[] displacement, int[] policy) {
    }

    private final Disk disk;
    private final int[] displacement = new int[HISTORY_SIZE];
    private final int[] policyOrdinal = new int[HISTORY_SIZE];
    private int next;
    private int size;
    private int lastCycle;
    private final Semaphore lock = new Semaphore(1);

    public DiskMetricsCollector(Disk disk) {
        if (disk == null) {
            throw new IllegalArgumentException("El colector necesita un disco");
        }
        this.disk = disk;
    }

    @Override
    public void onTick(int cycle) {
        DiskStatus status = disk.snapshot();      // fuera del lock: no toca el historial
        lock.acquireUninterruptibly();
        try {
            displacement[next] = status.totalDisplacement();
            policyOrdinal[next] = status.policy().ordinal();
            next = (next + 1) % HISTORY_SIZE;
            if (size < HISTORY_SIZE) {
                size++;
            }
            lastCycle = cycle;
        } finally {
            lock.release();
        }
    }

    // Métricas actuales: se piden al disco en el momento (no dependen de que haya corrido un ciclo).
    public DiskMetrics getMetrics() {
        return DiskMetrics.from(disk.snapshot());
    }

    public DisplacementSnapshot getSnapshot() {
        lock.acquireUninterruptibly();
        try {
            int[] d = new int[size];
            int[] p = new int[size];
            int start = (size < HISTORY_SIZE) ? 0 : next;     // lleno: la más vieja está en 'next'
            for (int i = 0; i < size; i++) {
                d[i] = displacement[(start + i) % HISTORY_SIZE];
                p[i] = policyOrdinal[(start + i) % HISTORY_SIZE];
            }
            return new DisplacementSnapshot(size == 0 ? 0 : lastCycle - size + 1, lastCycle, d, p);
        } finally {
            lock.release();
        }
    }
}
