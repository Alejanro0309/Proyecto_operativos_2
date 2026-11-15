package com.avilaos.tests;

import com.avilaos.disk.DiskCompletionListener;
import com.avilaos.disk.DiskRequest;
import com.avilaos.disk.DiskStatus;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;
import com.avilaos.enums.IoOperation;
import com.avilaos.hardware.Disk;

// Prueba del componente Disk con un reloj manual (llamando a onTick ciclo por ciclo).
// Usa las solicitudes de los casos P1 a P4 del enunciado, con el cabezal en 50 y 10 posiciones por ciclo.
public class DiskTest {

    private static final int TOTAL = 200;
    private static final int HEAD = 50;
    private static final int SPEED = 10;
    private static final int[] CASES = {95, 180, 34, 119, 11, 123, 62, 64};

    private static int passed = 0;
    private static int failed = 0;

    // Guarda el orden y el ciclo en que el disco termina cada solicitud
    private static class Recorder implements DiskCompletionListener {

        private final int[] blocks = new int[64];
        private final int[] cycles = new int[64];
        private int count;

        @Override
        public void onRequestCompleted(DiskRequest request, int cycle) {
            blocks[count] = request.getBlock();
            cycles[count] = cycle;
            count++;
        }

        String order() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                sb.append(i == 0 ? "" : ",").append(blocks[i]);
            }
            return sb.toString();
        }
    }

    public static void main(String[] args) {
        testPolicies();
        testWaitComparison();
        testArrivalRule();
        testMultiBlockAndSpeed();
        testIdleAndPolicyChange();
        testValidation();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    // Las 4 políticas con el mismo conjunto de solicitudes
    private static void testPolicies() {
        Recorder fifo = new Recorder();
        DiskStatus s = runCases(DiskPolicy.FIFO, fifo);
        check("FIFO: orden de llegada", fifo.order().equals("95,180,34,119,11,123,62,64"));
        check("FIFO: desplazamiento 644", s.totalDisplacement() == 644);
        check("FIFO: la primera termina en el ciclo 6 (45 posiciones = 5 ciclos + 1 de transferencia)",
                fifo.cycles[0] == 6);
        check("FIFO: se atendieron 8", s.served() == 8);
        check("FIFO: se cuentan en su política", s.servedPerPolicy()[DiskPolicy.FIFO.ordinal()] == 8);
        check("FIFO: el cabezal termina en el último bloque atendido", s.headPosition() == 64);
        check("FIFO: terminó, cola vacía y sin trabajo", !s.busy() && s.queue().length == 0);

        Recorder sstf = new Recorder();
        s = runCases(DiskPolicy.SSTF, sstf);
        check("SSTF: orden por cercanía", sstf.order().equals("62,64,34,11,95,119,123,180"));
        check("SSTF: desplazamiento 236", s.totalDisplacement() == 236);
        check("SSTF: la primera termina en el ciclo 3", sstf.cycles[0] == 3);

        Recorder scan = new Recorder();
        s = runCases(DiskPolicy.SCAN, scan);
        check("SCAN: sube y luego regresa", scan.order().equals("62,64,95,119,123,180,34,11"));
        check("SCAN: desplazamiento 337 (incluye ir hasta el 199)", s.totalDisplacement() == 337);
        check("SCAN: el cabezal queda bajando", s.direction() == HeadDirection.DOWN);

        Recorder cscan = new Recorder();
        s = runCases(DiskPolicy.C_SCAN, cscan);
        check("C-SCAN: sube, salta al 0 y vuelve a subir", cscan.order().equals("62,64,95,119,123,180,11,34"));
        check("C-SCAN: desplazamiento 183 (el salto de regreso no cuenta)", s.totalDisplacement() == 183);
        check("C-SCAN: el cabezal siempre sube", s.direction() == HeadDirection.UP);
    }

    // Con las mismas solicitudes, FIFO es la que más espera: sirve para las conclusiones del informe
    private static void testWaitComparison() {
        double fifo = runCases(DiskPolicy.FIFO, new Recorder()).averageWait();
        double sstf = runCases(DiskPolicy.SSTF, new Recorder()).averageWait();
        double scan = runCases(DiskPolicy.SCAN, new Recorder()).averageWait();
        double cscan = runCases(DiskPolicy.C_SCAN, new Recorder()).averageWait();
        check("FIFO: espera promedio 37.5 ciclos", Math.abs(fifo - 37.5) < 1e-9);
        check("SSTF espera menos que FIFO", sstf < fifo);
        check("SCAN espera menos que FIFO", scan < fifo);
        check("C-SCAN espera menos que FIFO", cscan < fifo);
    }

    // Una solicitud no compite hasta el ciclo siguiente al de su llegada
    private static void testArrivalRule() {
        Disk disk = newDisk(DiskPolicy.FIFO, SPEED, new Recorder());
        disk.submitRequest(new DiskRequest(60, IoOperation.READ, 1, 5));
        disk.onTick(5);
        check("llegada en el ciclo 5: en el ciclo 5 todavía no se atiende", !disk.snapshot().busy());
        check("pero ya figura en la cola", disk.snapshot().queue().length == 1);
        disk.onTick(6);
        check("en el ciclo 6 empieza a atenderse", disk.snapshot().busy());
        check("y sale de la cola", disk.snapshot().queue().length == 0);
    }

    private static void testMultiBlockAndSpeed() {
        Recorder rec = new Recorder();
        Disk disk = newDisk(DiskPolicy.FIFO, SPEED, rec);
        disk.submitRequest(new DiskRequest(HEAD, IoOperation.READ, 1, 0, 3));
        for (int c = 1; c <= 5; c++) {
            disk.onTick(c);
        }
        check("3 bloques en el bloque del cabezal: sin búsqueda, 3 ciclos de transferencia", rec.cycles[0] == 3);
        check("y no hubo desplazamiento", disk.snapshot().totalDisplacement() == 0);

        Recorder slow = new Recorder();
        Disk slowDisk = newDisk(DiskPolicy.FIFO, 1, slow);
        slowDisk.submitRequest(new DiskRequest(95, IoOperation.READ, 1, 0));
        for (int c = 1; c <= 60; c++) {
            slowDisk.onTick(c);
        }
        check("a 1 posición por ciclo, ir del 50 al 95 tarda 45 ciclos + 1", slow.cycles[0] == 46);
    }

    private static void testIdleAndPolicyChange() {
        Disk disk = newDisk(DiskPolicy.FIFO, SPEED, new Recorder());
        for (int c = 1; c <= 3; c++) {
            disk.onTick(c);
        }
        check("sin solicitudes: 3 ciclos ociosos", disk.snapshot().idleCycles() == 3);
        check("la política inicial se conserva", disk.snapshot().policy() == DiskPolicy.FIFO);
        disk.setPolicy(DiskPolicy.SCAN);
        check("se puede cambiar la política en ejecución", disk.snapshot().policy() == DiskPolicy.SCAN);
    }

    private static void testValidation() {
        Disk disk = newDisk(DiskPolicy.FIFO, SPEED, new Recorder());
        check("una solicitud fuera del disco se rechaza",
                throwsIAE(() -> disk.submitRequest(new DiskRequest(TOTAL, IoOperation.READ, 1, 0))));
        check("una solicitud nula se rechaza", throwsIAE(() -> disk.submitRequest(null)));
        check("cabezal inicial fuera del disco se rechaza",
                throwsIAE(() -> new Disk(TOTAL, TOTAL, SPEED, DiskPolicy.FIFO)));
        check("velocidad 0 se rechaza", throwsIAE(() -> new Disk(TOTAL, HEAD, 0, DiskPolicy.FIFO)));
        check("política nula se rechaza", throwsIAE(() -> disk.setPolicy(null)));
        check("una solicitud con 0 bloques se rechaza",
                throwsIAE(() -> new DiskRequest(1, IoOperation.READ, 1, 0, 0)));
    }

    // ---------------------------------------------------------------- auxiliares

    private static Disk newDisk(DiskPolicy policy, int speed, Recorder recorder) {
        Disk disk = new Disk(TOTAL, HEAD, speed, policy);
        disk.setCompletionListener(recorder);
        return disk;
    }

    // Deja las 8 solicitudes del caso en el ciclo 0 y corre el reloj hasta que el disco queda libre
    private static DiskStatus runCases(DiskPolicy policy, Recorder recorder) {
        Disk disk = newDisk(policy, SPEED, recorder);
        for (int i = 0; i < CASES.length; i++) {
            disk.submitRequest(new DiskRequest(CASES[i], IoOperation.READ, i + 1, 0));
        }
        for (int cycle = 1; cycle <= 5000; cycle++) {
            disk.onTick(cycle);
            DiskStatus status = disk.snapshot();
            if (!status.busy() && status.queue().length == 0) {
                break;
            }
        }
        return disk.snapshot();
    }

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
