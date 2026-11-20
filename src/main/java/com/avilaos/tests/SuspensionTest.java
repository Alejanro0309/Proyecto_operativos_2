/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.avilaos.tests;

import com.avilaos.disk.BlockMap;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.ProcessState;
import com.avilaos.enums.ProcessType;
import com.avilaos.enums.SchedulingPolicy;
import com.avilaos.filesystem.FileSystem;
import com.avilaos.hardware.Disk;
import com.avilaos.kernel.EventLogger;
import com.avilaos.kernel.Kernel;
import com.avilaos.model.ProcessControlBlock;

/**
 * Suspensión de procesos y planificador a mediano plazo (Proyecto 2, fase 6).
 *
 * Reglas que se comprueban (decisiones D1 a D7):
 *  D1 se suspende al bloqueado de menor prioridad; empate: el que se bloqueó primero.
 *  D2 se suspende para el primero de la fila (Listo/Suspendido antes que NUEVO),
 *     uno a la vez, y nunca si ni suspendiendo a todos los bloqueados alcanzaría.
 *  D3 al suspender, la RAM se libera cuando el disco termina de escribir;
 *     al reactivar, se reserva al decidir.
 *  D4 un Listo/Suspendido entra antes que un NUEVO.
 *  D5 las transferencias de swap pasan por la cola del disco.
 *  D6 con el swap lleno no se suspende.
 *  D7 el deadline sigue corriendo en el swap.
 *
 * En TODAS las pruebas se revisan en cada ciclo las invariantes: la RAM usada
 * coincide con los procesos que la ocupan, los bloques de swap usados coinciden
 * con los procesos suspendidos y cada proceso está en la lista de su estado.
 *
 * Ejecutar en NetBeans: clic derecho sobre este archivo -> Run File (Shift+F6).
 *
 * @author SebMarval
 */
public class SuspensionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testSuspendToAdmitNewProcess();
        testVictimTieBreak();
        testNoUselessSuspension();
        testSwapFull();
        testReadySuspendedDisplacesBlocked();
        testReadySuspendedBeforeNew();
        testDeadlineInSwap();
        testStressInvariants();
        testThreadsAreDeterministic();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    // ================================================================ escenario

    /** Un kernel con poca RAM, un disco rápido y el sistema de archivos del enunciado. */
    private static final class Rig {
        final BlockMap map;
        final FileSystem fs;
        final Disk disk;
        final Kernel kernel;
        final StringBuilder log = new StringBuilder();
        int cycle = 0;
        String violation = null;

        Rig(int ram, int swapBlocks) {
            map = new BlockMap(200, swapBlocks);
            fs = new FileSystem(map);
            fs.loadFile("/mbr.bin", 0, 11, "admin", FilePermission.PUBLIC, "carga");
            fs.createDirectory("/sistema", "admin", FilePermission.PUBLIC, "carga");
            fs.loadFile("/sistema/config.sys", 95, 24, "admin", FilePermission.PUBLIC, "carga");
            disk = new Disk(200, 50, 10, DiskPolicy.FIFO);
            EventLogger logger = (c, id, m) -> log.append("[").append(c).append("] ").append(m).append('\n');
            kernel = new Kernel(0, ram, SchedulingPolicy.FCFS, 3, logger);
            kernel.attachDisk(disk, fs);
            disk.setCompletionListener(kernel);
        }

        void tick() {
            cycle++;
            kernel.runCycle(cycle);
            disk.onTick(cycle);
            if (violation == null) {
                violation = checkInvariants(this);
            }
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                tick();
            }
        }

        void runUntilIdle(int maxCycles) {
            while (cycle < maxCycles && kernel.getActiveProcessCount() > 0) {
                tick();
            }
        }

        int swapUsed() {
            return map.getSwapAreaBlocks() - map.freeSwapBlocks();
        }
    }

    /** Bloqueado por E/S (DMA) durante 'blockCycles' después de su primera instrucción. */
    private static ProcessControlBlock blocker(String name, int priority, int blockCycles, int deadline) {
        ProcessControlBlock p = new ProcessControlBlock(name, 0, ProcessType.IO_BOUND, priority, 16, deadline, 3);
        p.configureIO(1, blockCycles);
        return p;
    }

    private static ProcessControlBlock cpu(String name, int priority, int memory, int instructions) {
        return new ProcessControlBlock(name, 0, ProcessType.CPU_BOUND, priority, memory, 5000, instructions);
    }

    // ================================================================ pruebas

    /** El caso base: un NUEVO no cabe, se suspende a un bloqueado, entra el nuevo y luego vuelve el suspendido. */
    private static void testSuspendToAdmitNewProcess() {
        Rig r = new Rig(32, 100);
        ProcessControlBlock a = blocker("A", 1, 120, 5000);
        ProcessControlBlock b = blocker("B", 5, 120, 5000);
        r.kernel.submit(a);
        r.kernel.submit(b);
        r.ticks(3);
        check("A y B quedan bloqueados ocupando toda la RAM",
                a.getState() == ProcessState.BLOCKED && b.getState() == ProcessState.BLOCKED
                && r.kernel.getRam().getFree() == 0);

        ProcessControlBlock n = cpu("N", 0, 16, 5);
        r.kernel.submit(n);
        r.tick();
        check("D1: se suspende al de menor prioridad (B, prioridad 5)",
                b.getState() == ProcessState.BLOCKED_SUSPENDED && a.getState() == ProcessState.BLOCKED);
        check("el suspendido conserva su motivo de bloqueo (IO_WAIT)",
                b.getBlockReason() == com.avilaos.enums.BlockReason.IO_WAIT);
        check("el log explica la decisión (\"Se suspende al proceso P" + b.getId() + " para admitir a P" + n.getId() + "\")",
                r.log.toString().contains("Se suspende al proceso P" + b.getId() + " para admitir a P" + n.getId()));
        check("se ocupan 16 bloques del swap", r.swapUsed() == 16 && b.getSwapStartBlock() == 200);
        check("D3: la RAM sigue ocupada mientras el disco escribe", r.kernel.getRam().getFree() == 0
                && b.getSwapInProgress() == IoOperation.SUSPEND);
        check("D5: la escritura al swap está en la cola del disco", r.disk.snapshot().queue().length
                + (r.disk.snapshot().current() != null ? 1 : 0) == 1);

        int waited = 0;
        while (n.getState() == ProcessState.NEW && waited < 200) {
            r.tick();
            waited++;
        }
        check("D3: cuando el disco termina se libera la RAM y entra N (" + waited + " ciclos)",
                n.getState() != ProcessState.NEW && b.getSwapInProgress() == null);

        r.runUntilIdle(1000);
        check("el evento de B ocurrió en el swap y luego B volvió y terminó", b.getState() == ProcessState.TERMINATED
                && !b.isDeadlineMissed() && r.log.toString().contains("P" + b.getId() + " termina IO_WAIT (DMA) -> READY_SUSPENDED"));
        check("1 suspensión y 1 reactivación", r.kernel.getSuspensionCount() == 1 && r.kernel.getReactivationCount() == 1);
        check("al final no queda nada en RAM ni en el swap", r.kernel.getRam().getUsed() == 0 && r.swapUsed() == 0);
        check("invariantes de RAM, swap y colas en todos los ciclos" + reason(r), r.violation == null);
    }

    /** D1, desempate: misma prioridad, se suspende al que se bloqueó primero. */
    private static void testVictimTieBreak() {
        Rig r = new Rig(32, 100);
        ProcessControlBlock first = blocker("Primero", 3, 200, 5000);
        ProcessControlBlock second = blocker("Segundo", 3, 200, 5000);
        r.kernel.submit(first);
        r.kernel.submit(second);
        r.ticks(3);
        r.kernel.submit(cpu("N", 0, 16, 5));
        r.tick();
        check("D1 empate: se suspende al que se bloqueó primero",
                first.getState() == ProcessState.BLOCKED_SUSPENDED && second.getState() == ProcessState.BLOCKED);
        r.runUntilIdle(1500);
        check("invariantes" + reason(r), r.violation == null);
    }

    /** D2: si ni suspendiendo a todos los bloqueados alcanza, no se suspende a nadie. */
    private static void testNoUselessSuspension() {
        Rig r = new Rig(48, 100);
        ProcessControlBlock a = blocker("A", 5, 60, 5000);
        ProcessControlBlock big = cpu("Grande", 1, 32, 80);
        r.kernel.submit(a);
        r.kernel.submit(big);
        r.ticks(3);
        ProcessControlBlock n = cpu("N", 0, 32, 5);
        r.kernel.submit(n);
        r.ticks(5);
        check("N necesita 32 y solo hay 16 suspendibles: nadie se suspende",
                r.kernel.getSuspensionCount() == 0 && a.getState() == ProcessState.BLOCKED && n.getState() == ProcessState.NEW);
        r.runUntilIdle(2000);
        check("N entra cuando la memoria se libera sola y todos terminan",
                n.getState() == ProcessState.TERMINATED && r.kernel.getSuspensionCount() == 0);
        check("invariantes" + reason(r), r.violation == null);
    }

    /** D6: con el swap lleno no se suspende; el nuevo espera. */
    private static void testSwapFull() {
        Rig r = new Rig(32, 10);                 // swap de 10 bloques: no cabe un proceso de 16
        ProcessControlBlock a = blocker("A", 1, 40, 5000);
        ProcessControlBlock b = blocker("B", 5, 40, 5000);
        r.kernel.submit(a);
        r.kernel.submit(b);
        r.ticks(3);
        ProcessControlBlock n = cpu("N", 0, 16, 5);
        r.kernel.submit(n);
        r.tick();
        check("D6: swap lleno, nadie se suspende y el log lo dice",
                r.kernel.getSuspensionCount() == 0 && r.log.toString().contains("Swap lleno"));
        r.runUntilIdle(2000);
        check("N termina cuando A y B liberan la RAM", n.getState() == ProcessState.TERMINATED);
        check("invariantes" + reason(r), r.violation == null);
    }

    /** D2 corregida: un Listo/Suspendido puede desplazar a un bloqueado (no se queda esperando). */
    private static void testReadySuspendedDisplacesBlocked() {
        Rig r = new Rig(32, 100);
        ProcessControlBlock a = blocker("A", 1, 400, 5000);      // bloqueado mucho tiempo
        ProcessControlBlock b = blocker("B", 5, 30, 5000);       // su evento llega pronto
        r.kernel.submit(a);
        r.kernel.submit(b);
        r.ticks(3);
        ProcessControlBlock n = cpu("N", 0, 16, 300);            // ocupa la CPU un buen rato
        r.kernel.submit(n);
        int guard = 0;
        while (b.getState() != ProcessState.READY_SUSPENDED && guard++ < 300) {
            r.tick();
        }
        check("B se suspendió y su evento ocurrió en el swap: READY_SUSPENDED",
                b.getState() == ProcessState.READY_SUSPENDED);
        guard = 0;
        while (a.getState() == ProcessState.BLOCKED && guard++ < 300) {
            r.tick();
        }
        check("para reactivar a B se suspende a A (bloqueado): \"para reactivar a P" + b.getId() + "\"",
                a.getState() == ProcessState.BLOCKED_SUSPENDED
                && r.log.toString().contains("Se suspende al proceso P" + a.getId() + " para reactivar a P" + b.getId()));
        r.runUntilIdle(3000);
        String trace = r.log.toString();
        int admittedN = trace.indexOf("P" + n.getId() + " admitido");
        check("uno a la vez: la RAM que liberó B fue para N, no para B (que se puso antes en la fila)",
                admittedN >= 0 && admittedN < trace.indexOf("Se reactiva al proceso P" + b.getId()));
        check("todos terminan sin vencer su deadline", a.getState() == ProcessState.TERMINATED
                && b.getState() == ProcessState.TERMINATED && !a.isDeadlineMissed() && !b.isDeadlineMissed());
        check("invariantes" + reason(r), r.violation == null);
    }

    /** D4: con memoria libre, un Listo/Suspendido entra antes que un NUEVO que llegó después. */
    private static void testReadySuspendedBeforeNew() {
        Rig r = new Rig(32, 100);
        ProcessControlBlock a = blocker("A", 1, 30, 5000);
        ProcessControlBlock b = blocker("B", 5, 30, 5000);
        r.kernel.submit(a);
        r.kernel.submit(b);
        r.ticks(3);
        ProcessControlBlock n = cpu("N", 0, 16, 120);
        r.kernel.submit(n);
        int guard = 0;
        while (b.getState() != ProcessState.READY_SUSPENDED && guard++ < 300) {
            r.tick();
        }
        ProcessControlBlock late = cpu("Tarde", 0, 16, 5);
        r.kernel.submit(late);
        r.runUntilIdle(3000);
        String log = r.log.toString();
        int reactivated = log.indexOf("Se reactiva al proceso P" + b.getId());
        int admitted = log.indexOf("P" + late.getId() + " admitido");
        check("D4: B (Listo/Suspendido) se reactiva antes de admitir a Tarde (NUEVO)",
                reactivated >= 0 && admitted > reactivated);
        check("invariantes" + reason(r), r.violation == null);
    }

    /** D7: el deadline sigue corriendo en el swap; al vencer se liberan sus bloques. */
    private static void testDeadlineInSwap() {
        Rig r = new Rig(32, 100);
        ProcessControlBlock a = blocker("A", 1, 200, 5000);
        ProcessControlBlock b = blocker("B", 5, 200, 60);        // vence estando en el swap
        r.kernel.submit(a);
        r.kernel.submit(b);
        r.ticks(3);
        r.kernel.submit(cpu("N", 0, 16, 300));
        r.ticks(70);
        check("D7: B vence su deadline en el swap", b.getState() == ProcessState.TERMINATED && b.isDeadlineMissed());
        check("sus bloques de swap quedan libres", r.swapUsed() == 0);
        r.runUntilIdle(3000);
        check("invariantes" + reason(r), r.violation == null);
    }

    /**
     * Carga mixta con poca RAM: procesos de CPU, de E/S por DMA y de archivos,
     * prioridades variadas y deadlines. Se revisan las invariantes en cada ciclo
     * y que todos terminen (no hay bucle de suspensiones ni bloqueo permanente).
     */
    private static void testStressInvariants() {
        Rig r = new Rig(64, 64);
        ProcessControlBlock[] all = new ProcessControlBlock[30];
        int seed = 7;
        for (int i = 0; i < all.length; i++) {
            seed = (seed * 1103515245 + 12345) & 0x7fffffff;
            int kind = seed % 3;
            int priority = (seed / 3) % 6;
            if (kind == 0) {
                all[i] = cpu("C" + i, priority, 16, 5 + seed % 20);
            } else if (kind == 1) {
                all[i] = blocker("E" + i, priority, 10 + seed % 60, 3000);
            } else {
                all[i] = ProcessControlBlock.createFileIoProcess("F" + i, 0, priority, 16, 3000, 3,
                        IoOperation.READ, "/sistema/config.sys");
            }
        }
        int next = 0;
        while (r.cycle < 6000 && (next < all.length || r.kernel.getActiveProcessCount() > 0)) {
            if (next < all.length && r.cycle % 7 == 0) {
                r.kernel.submit(all[next++]);
            }
            r.tick();
        }
        boolean allDone = true;
        for (ProcessControlBlock p : all) {
            allDone &= p.getState() == ProcessState.TERMINATED;
        }
        check("carga mixta: los 30 procesos terminan (" + r.kernel.getSuspensionCount() + " suspensiones, "
                + r.kernel.getReactivationCount() + " reactivaciones)", allDone);
        check("hubo suspensiones y cada una tuvo sentido (no más de 2 por proceso)",
                r.kernel.getSuspensionCount() > 0 && r.kernel.getSuspensionCount() <= 2 * all.length);
        check("al final la RAM y el swap quedan vacíos", r.kernel.getRam().getUsed() == 0 && r.swapUsed() == 0);
        check("invariantes en los " + r.cycle + " ciclos" + reason(r), r.violation == null);
    }

    /** Con hilos reales (computador y disco en paralelo) la suspensión da los mismos ciclos que con ticks manuales. */
    private static void testThreadsAreDeterministic() {
        int[] manual = runMixedWorkload(false);
        boolean same = manual != null;
        for (int round = 0; round < 5 && same; round++) {
            int[] threaded = runMixedWorkload(true);
            same = threaded != null;
            for (int i = 0; same && i < manual.length; i++) {
                same = manual[i] == threaded[i];
            }
        }
        check("con hilos reales cada proceso termina en el mismo ciclo que con ticks manuales (5 corridas)", same);
    }

    /** 12 procesos mixtos con RAM de 48; devuelve el ciclo de fin de cada uno (null si no terminan). */
    private static int[] runMixedWorkload(boolean threads) {
        BlockMap map = new BlockMap(200, 64);
        FileSystem fs = new FileSystem(map);
        fs.loadFile("/datos.bin", 120, 10, "admin", FilePermission.PUBLIC, "carga");
        Disk disk = new Disk(200, 50, 10, DiskPolicy.FIFO);
        com.avilaos.system.Cluster c = new com.avilaos.system.Cluster(48, SchedulingPolicy.FCFS, 3, 0, disk, fs,
                EventLogger.SILENT);
        ProcessControlBlock[] ps = new ProcessControlBlock[12];
        for (int i = 0; i < ps.length; i++) {
            if (i % 3 == 0) {
                ps[i] = cpu("C" + i, i % 4, 16, 8 + i);
            } else if (i % 3 == 1) {
                ps[i] = blocker("E" + i, i % 5, 20 + 3 * i, 5000);
            } else {
                ps[i] = ProcessControlBlock.createFileIoProcess("F" + i, 0, i % 6, 16, 5000, 3,
                        IoOperation.READ, "/datos.bin");
            }
            c.submit(ps[i]);
        }
        Kernel k = c.getComputer(0).getKernel();
        if (threads) {
            c.start();
            long end = System.currentTimeMillis() + 10000;
            while (k.getActiveProcessCount() > 0 && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            c.stop();
        } else {
            int cycle = 0;
            while (k.getActiveProcessCount() > 0 && cycle < 10000) {
                cycle++;
                c.getComputer(0).onTick(cycle);
                disk.onTick(cycle);
            }
        }
        if (k.getActiveProcessCount() > 0 || k.getSuspensionCount() == 0) {
            return null;                         // no terminó, o no hubo suspensiones que probar
        }
        int[] finish = new int[ps.length];
        for (int i = 0; i < ps.length; i++) {
            finish[i] = ps[i].getFinishCycle();
        }
        return finish;
    }

    // ================================================================ invariantes

    /** Devuelve null si todo cuadra, o la primera inconsistencia encontrada. */
    private static String checkInvariants(Rig r) {
        Kernel k = r.kernel;
        int ram = 0;
        int swap = 0;
        Object[][] lists = {k.getReadySnapshot(), k.getBlockedSnapshot(), k.getReadySuspendedSnapshot(),
            k.getBlockedSuspendedSnapshot()};
        ProcessState[] states = {ProcessState.READY, ProcessState.BLOCKED, ProcessState.READY_SUSPENDED,
            ProcessState.BLOCKED_SUSPENDED};
        for (int l = 0; l < lists.length; l++) {
            for (Object o : lists[l]) {
                ProcessControlBlock p = (ProcessControlBlock) o;
                if (p.getState() != states[l]) {
                    return "ciclo " + r.cycle + ": P" + p.getId() + " en la lista " + states[l] + " con estado " + p.getState();
                }
                boolean suspended = l >= 2;
                if (!suspended || p.getSwapInProgress() != null) {
                    ram += p.getMemoryRequired();
                }
                if (p.getSwapStartBlock() >= 0) {
                    swap += p.getMemoryRequired();
                }
                if (suspended && p.getSwapStartBlock() < 0) {
                    return "ciclo " + r.cycle + ": P" + p.getId() + " suspendido sin bloques en el swap";
                }
            }
        }
        if (k.getCpu().getRunning() != null) {
            ram += k.getCpu().getRunning().getMemoryRequired();
        }
        if (ram != k.getRam().getUsed()) {
            return "ciclo " + r.cycle + ": RAM usada " + k.getRam().getUsed() + " pero los procesos suman " + ram;
        }
        if (swap != r.swapUsed()) {
            return "ciclo " + r.cycle + ": swap usado " + r.swapUsed() + " pero los suspendidos suman " + swap;
        }
        return null;
    }

    private static String reason(Rig r) {
        return r.violation == null ? "" : " -> " + r.violation;
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
