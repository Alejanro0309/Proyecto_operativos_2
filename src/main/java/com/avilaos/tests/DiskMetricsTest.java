package com.avilaos.tests;

import com.avilaos.disk.DiskRequest;
import com.avilaos.disk.DiskStatus;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.IoOperation;
import com.avilaos.hardware.Disk;
import com.avilaos.metrics.DiskMetrics;
import com.avilaos.metrics.DiskMetricsCollector;

// Prueba de las métricas del disco y del historial del desplazamiento acumulado (la gráfica del cabezal).
// Usa los casos P1 a P4 del enunciado (cabezal en 50, 8 solicitudes, 10 posiciones por ciclo) con reloj manual:
// en cada ciclo corre el disco y después el colector, como hace el reloj global.
public class DiskMetricsTest {

    private static final int[] CASES = {95, 180, 34, 119, 11, 123, 62, 64};

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testFifoMetrics();
        testHistory();
        testPolicyChangeInHistory();
        testMetricsFromStatus();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    private static Disk newDisk(DiskPolicy policy) {
        Disk disk = new Disk(200, 50, 10, policy);
        for (int i = 0; i < CASES.length; i++) {
            disk.submitRequest(new DiskRequest(CASES[i], IoOperation.READ, i + 1, 0));
        }
        return disk;
    }

    // Corre hasta que el disco queda libre y devuelve cuántos ciclos corrieron
    private static int runUntilIdle(Disk disk, DiskMetricsCollector collector, int maxCycles) {
        int cycle = 0;
        while (cycle < maxCycles) {
            cycle++;
            disk.onTick(cycle);
            collector.onTick(cycle);
            DiskStatus s = disk.snapshot();
            if (!s.busy() && s.queue().length == 0) {
                break;
            }
        }
        return cycle;
    }

    private static void testFifoMetrics() {
        Disk disk = newDisk(DiskPolicy.FIFO);
        DiskMetricsCollector collector = new DiskMetricsCollector(disk);
        int cycles = runUntilIdle(disk, collector, 500);
        DiskMetrics m = collector.getMetrics();
        check("FIFO: desplazamiento total 644", m.totalDisplacement() == 644);
        check("FIFO: 8 solicitudes atendidas", m.served() == 8);
        check("FIFO: espera promedio 37.5 ciclos", Math.abs(m.averageWait() - 37.5) < 1e-9);
        check("FIFO: el disco nunca estuvo ocioso (llegaron todas en el ciclo 0)",
                m.idleCycles() == 0 && m.busyCycles() == cycles);
        check("FIFO: utilización 100 %", Math.abs(m.utilization() - 100.0) < 1e-9);
        check("FIFO: las 8 se cuentan en su política", m.servedPerPolicy()[DiskPolicy.FIFO.ordinal()] == 8);
    }

    private static void testHistory() {
        Disk disk = newDisk(DiskPolicy.FIFO);
        DiskMetricsCollector collector = new DiskMetricsCollector(disk);
        int cycles = runUntilIdle(disk, collector, 500);
        DiskMetricsCollector.DisplacementSnapshot snap = collector.getSnapshot();
        check("historial: una muestra por ciclo (" + cycles + ")", snap.displacement().length == cycles);
        check("historial: cubre del ciclo 1 al " + cycles, snap.oldestCycle() == 1 && snap.latestCycle() == cycles);
        check("historial: en el ciclo 1 el cabezal ya recorrió 10 posiciones", snap.displacement()[0] == 10);
        boolean nonDecreasing = true;
        for (int i = 1; i < snap.displacement().length; i++) {
            nonDecreasing &= snap.displacement()[i] >= snap.displacement()[i - 1];
        }
        check("historial: el desplazamiento acumulado nunca baja", nonDecreasing);
        check("historial: termina en 644", snap.displacement()[cycles - 1] == 644);
        boolean allFifo = true;
        for (int i = 0; i < snap.policy().length; i++) {
            allFifo &= snap.policy()[i] == DiskPolicy.FIFO.ordinal();
        }
        check("historial: todas las muestras son de la política FIFO", allFifo);
    }

    // Al cambiar de política en ejecución, la muestra guarda cuál estaba activa (así la gráfica cambia de serie)
    private static void testPolicyChangeInHistory() {
        Disk disk = newDisk(DiskPolicy.FIFO);
        DiskMetricsCollector collector = new DiskMetricsCollector(disk);
        for (int cycle = 1; cycle <= 5; cycle++) {
            disk.onTick(cycle);
            collector.onTick(cycle);
        }
        disk.setPolicy(DiskPolicy.SSTF);
        for (int cycle = 6; cycle <= 500; cycle++) {
            disk.onTick(cycle);
            collector.onTick(cycle);
            DiskStatus s = disk.snapshot();
            if (!s.busy() && s.queue().length == 0) {
                break;
            }
        }
        DiskMetricsCollector.DisplacementSnapshot snap = collector.getSnapshot();
        DiskMetrics m = collector.getMetrics();
        check("cambio de política: las primeras muestras son FIFO", snap.policy()[0] == DiskPolicy.FIFO.ordinal());
        check("cambio de política: las últimas son SSTF",
                snap.policy()[snap.policy().length - 1] == DiskPolicy.SSTF.ordinal());
        check("cambio de política: la solicitud ya elegida con FIFO se cuenta en FIFO y las demás en SSTF",
                m.servedPerPolicy()[DiskPolicy.FIFO.ordinal()] == 1 && m.servedPerPolicy()[DiskPolicy.SSTF.ordinal()] == 7);
        check("cambio de política: se atendieron las 8", m.served() == 8);
    }

    private static void testMetricsFromStatus() {
        Disk idle = new Disk(200, 50, 10, DiskPolicy.FIFO);
        DiskMetricsCollector collector = new DiskMetricsCollector(idle);
        DiskMetrics none = collector.getMetrics();
        check("sin ciclos: espera y utilización no aplican (NaN)",
                Double.isNaN(none.averageWait()) && Double.isNaN(none.utilization()));
        idle.onTick(1);
        idle.onTick(2);
        DiskMetrics idleMetrics = collector.getMetrics();
        check("2 ciclos sin solicitudes: utilización 0 % y 2 ociosos",
                Math.abs(idleMetrics.utilization()) < 1e-9 && idleMetrics.idleCycles() == 2);
        check("el colector rechaza un disco nulo", throwsIAE(() -> new DiskMetricsCollector(null)));
    }

    // ---------------------------------------------------------------- auxiliares

    private static boolean throwsIAE(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("[OK]    " + name);
        } else {
            failed++;
            System.out.println("[FALLA] " + name);
        }
    }
}
