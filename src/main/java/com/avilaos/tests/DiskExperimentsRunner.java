/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.avilaos.tests;

import com.avilaos.disk.BlockMap;
import com.avilaos.disk.DiskCompletionListener;
import com.avilaos.disk.DiskRequest;
import com.avilaos.disk.DiskStatus;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.SchedulingPolicy;
import com.avilaos.filesystem.FileSystem;
import com.avilaos.hardware.Disk;
import com.avilaos.kernel.EventLogger;
import com.avilaos.kernel.Kernel;
import com.avilaos.metrics.DiskMetrics;
import com.avilaos.model.ProcessControlBlock;
import com.avilaos.model.ProcessIdGenerator;
import com.avilaos.system.Cluster;

/**
 * Experimentos reproducibles del Proyecto 2 para las conclusiones del informe:
 *  A. Casos P1 a P4 del enunciado con las 4 políticas de disco.
 *  B. Carga grande (30 solicitudes escalonadas) para comparar desplazamiento,
 *     espera promedio y riesgo de inanición (espera máxima) en SSTF.
 *  C. Efecto de la suspensión de procesos: misma carga con RAM holgada (sin
 *     suspensión) vs. RAM reducida (con suspensión y uso del swap).
 *
 * Todo corre con ticks manuales (determinista, sin hilos ni colecciones de Java).
 * Ejecutar en NetBeans: clic derecho sobre este archivo -> Run File (Shift+F6).
 *
 * @author SebMarval
 */
public class DiskExperimentsRunner {

    private static final int TOTAL_BLOCKS = 200;
    private static final int INITIAL_HEAD = 50;
    private static final int HEAD_SPEED = 10;
    private static final int MAX_CYCLES = 10000;

    // Casos P1 a P4 del enunciado
    private static final int[] OFFICIAL_CASES = {95, 180, 34, 119, 11, 123, 62, 64};

    // Experimento B: 30 solicitudes {bloque, cicloDeLlegada}.
    // Mezcla peticiones tempranas en los extremos (5, 195, 12, 188) con un flujo
    // sostenido en la zona central (45..75) para exponer la inanición en SSTF.
    private static final int[][] LARGE_WORKLOAD = {
        {5,   0}, {195, 0}, {52,  0}, {48,  0}, {60,  1},
        {12,  2}, {55,  3}, {46,  4}, {188, 5}, {63,  6},
        {50,  8}, {58, 10}, {44, 12}, {66, 14}, {53, 16},
        {18, 18}, {49, 20}, {61, 22}, {175,24}, {56, 26},
        {47, 28}, {64, 30}, {51, 32}, {140,34}, {59, 36},
        {45, 38}, {62, 40}, {25, 42}, {54, 44}, {160,46}
    };

    public static void main(String[] args) {
        experimentA();
        experimentB();
        experimentC();
    }

    // ===================================================================== A
    private static void experimentA() {
        System.out.println("## Experimento A — Casos P1 a P4 del enunciado (8 solicitudes, cabezal en 50, velocidad 10)");
        System.out.println();
        System.out.println("| Política | Orden de atención | Desplazamiento total | Ciclos totales | Espera prom. (ciclos) | Espera máx. (ciclos) | Utilización disco |");
        System.out.println("|---|---|---|---|---|---|---|");

        int[][] reqs = new int[OFFICIAL_CASES.length][2];
        for (int i = 0; i < OFFICIAL_CASES.length; i++) {
            reqs[i][0] = OFFICIAL_CASES[i];
            reqs[i][1] = 0;
        }
        for (DiskPolicy policy : DiskPolicy.values()) {
            DiskRun r = runDiskTrace(policy, reqs);
            System.out.printf("| %s | %s | %d | %d | %.2f | %d | %.1f %% |%n",
                    policy, r.order, r.displacement, r.cycles, r.avgWait, r.maxWait, r.utilization);
        }
        System.out.println();
    }

    // ===================================================================== B
    private static void experimentB() {
        System.out.println("## Experimento B — Carga grande y escalonada (30 solicitudes, extremos + zona media)");
        System.out.println();
        System.out.println("| Política | Solicitudes atendidas | Desplazamiento total | Ciclos totales | Espera prom. (ciclos) | Espera máx. (ciclos) | Bloque con mayor espera |");
        System.out.println("|---|---|---|---|---|---|---|");

        for (DiskPolicy policy : DiskPolicy.values()) {
            DiskRun r = runDiskTrace(policy, LARGE_WORKLOAD);
            System.out.printf("| %s | %d | %d | %d | %.2f | %d | %d |%n",
                    policy, r.served, r.displacement, r.cycles, r.avgWait, r.maxWait, r.maxWaitBlock);
        }
        System.out.println();
    }

    private record DiskRun(String order, int served, int displacement, int cycles,
            double avgWait, int maxWait, int maxWaitBlock, double utilization) {
    }

    /** Ejecuta un conjunto de solicitudes {bloque, llegada} sobre Disk con ticks manuales. */
    private static DiskRun runDiskTrace(DiskPolicy policy, int[][] requests) {
        Disk disk = new Disk(TOTAL_BLOCKS, INITIAL_HEAD, HEAD_SPEED, policy);
        final int n = requests.length;
        final boolean[] started = new boolean[n + 1];
        final int[] maxWait = {0};
        final int[] maxWaitBlock = {-1};
        final StringBuilder order = new StringBuilder();

        disk.setCompletionListener(new DiskCompletionListener() {
            @Override
            public void onRequestCompleted(DiskRequest request, int cycle) {
                int pid = request.getProcessId();
                if (!started[pid]) {
                    // Solicitud que empezó y terminó en el mismo ciclo (0 búsqueda, 1 bloque)
                    started[pid] = true;
                    int wait = cycle - request.getArrivalCycle();
                    if (wait > maxWait[0]) {
                        maxWait[0] = wait;
                        maxWaitBlock[0] = request.getBlock();
                    }
                }
                if (order.length() > 0) {
                    order.append(" → ");
                }
                order.append(request.getBlock());
            }
        });

        int submitted = 0;
        // Llegadas en el ciclo 0 (antes del primer tick)
        for (int i = 0; i < n; i++) {
            if (requests[i][1] == 0) {
                disk.submitRequest(new DiskRequest(requests[i][0], IoOperation.READ, i + 1, 0));
                submitted++;
            }
        }

        int cycle = 0;
        while (cycle < MAX_CYCLES) {
            cycle++;
            // Llegadas del ciclo actual (entran a competir en el ciclo siguiente, como en el sistema real)
            for (int i = 0; i < n; i++) {
                if (requests[i][1] == cycle) {
                    disk.submitRequest(new DiskRequest(requests[i][0], IoOperation.READ, i + 1, cycle));
                    submitted++;
                }
            }
            disk.onTick(cycle);
            DiskStatus st = disk.snapshot();
            if (st.current() != null) {
                int pid = st.current().getProcessId();
                if (!started[pid]) {
                    started[pid] = true;
                    int wait = cycle - st.current().getArrivalCycle();
                    if (wait > maxWait[0]) {
                        maxWait[0] = wait;
                        maxWaitBlock[0] = st.current().getBlock();
                    }
                }
            }
            if (submitted == n && !st.busy() && st.queue().length == 0) {
                break;
            }
        }
        DiskMetrics m = DiskMetrics.from(disk.snapshot());
        return new DiskRun(order.toString(), m.served(), m.totalDisplacement(), cycle,
                m.averageWait(), maxWait[0], maxWaitBlock[0], m.utilization());
    }

    // ===================================================================== C
    private static void experimentC() {
        System.out.println("## Experimento C — Efecto de la suspensión de procesos (10 procesos FILE_IO de 16 MB, swap 100 bloques, SSTF)");
        System.out.println();
        System.out.println("| RAM | Escenario | Ciclos totales | Completados | Deadlines vencidos | Suspensiones | Reactivaciones | Pico de swap (bloques) | Desplazamiento disco |");
        System.out.println("|---|---|---|---|---|---|---|---|---|");

        int[] rams = {256, 64, 48, 32};
        String[] labels = {"Sin suspensión", "RAM ajustada", "RAM reducida", "RAM crítica"};
        for (int i = 0; i < rams.length; i++) {
            runSuspensionCase(rams[i], labels[i]);
        }
        System.out.println();
    }

    private static void runSuspensionCase(int ramCapacity, String label) {
        ProcessIdGenerator.reset();
        BlockMap map = new BlockMap(TOTAL_BLOCKS, 100);
        FileSystem fs = new FileSystem(map);
        loadOfficialFileSystem(fs);
        Disk disk = new Disk(TOTAL_BLOCKS, INITIAL_HEAD, HEAD_SPEED, DiskPolicy.SSTF);
        Cluster cluster = new Cluster(ramCapacity, SchedulingPolicy.FCFS, 0, 0, disk, fs, EventLogger.SILENT);

        String[] paths = {
            "/sistema/config.sys", "/usuarios/video.mp4", "/sistema/boot.bin",
            "/usuarios/data.csv", "/sistema/script.py", "/sistema/core.dll",
            "/sistema/config.sys", "/usuarios/video.mp4", "/sistema/boot.bin",
            "/usuarios/data.csv"
        };
        int[] priorities = {4, 3, 2, 1, 5, 2, 3, 1, 4, 1};
        for (int i = 0; i < paths.length; i++) {
            ProcessControlBlock p = ProcessControlBlock.createFileIoProcess(
                    "IO_" + (i + 1), 0, priorities[i], 16, 420, 2, IoOperation.READ, paths[i]);
            cluster.submit(p);
        }

        Kernel kernel = cluster.getComputer(0).getKernel();
        int cycle = 0;
        int peakSwap = 0;
        while (cycle < MAX_CYCLES && (kernel.getActiveProcessCount() > 0 || disk.snapshot().busy())) {
            cycle++;
            cluster.getComputer(0).onTick(cycle);
            disk.onTick(cycle);
            int swapUsed = map.getSwapAreaBlocks() - map.freeSwapBlocks();
            if (swapUsed > peakSwap) {
                peakSwap = swapUsed;
            }
        }
        DiskStatus ds = disk.snapshot();
        System.out.printf("| %d | %s | %d | %d | %d | %d | %d | %d | %d |%n",
                ramCapacity, label, cycle, kernel.getCompletedCount(), kernel.getDeadlineMissedCount(),
                kernel.getSuspensionCount(), kernel.getReactivationCount(), peakSwap, ds.totalDisplacement());
    }

    /** El sistema de archivos oficial del enunciado (200 bloques). */
    private static void loadOfficialFileSystem(FileSystem fs) {
        FilePermission pub = FilePermission.PUBLIC;
        FilePermission priv = FilePermission.PRIVATE;
        fs.loadFile("/mbr.bin", 0, 11, "admin", pub, "carga");
        fs.createDirectory("/sistema", "admin", pub, "carga");
        fs.createDirectory("/usuarios", "admin", pub, "carga");
        fs.loadFile("/sistema/boot.bin", 11, 23, "admin", pub, "carga");
        fs.loadFile("/usuarios/notas.txt", 34, 28, "user1", priv, "carga");
        fs.loadFile("/sistema/script.py", 62, 2, "admin", pub, "carga");
        fs.loadFile("/sistema/core.dll", 64, 31, "admin", pub, "carga");
        fs.loadFile("/sistema/config.sys", 95, 24, "admin", pub, "carga");
        fs.loadFile("/usuarios/img1.png", 119, 4, "user1", priv, "carga");
        fs.loadFile("/usuarios/data.csv", 123, 57, "user2", pub, "carga");
        fs.loadFile("/usuarios/video.mp4", 180, 20, "user1", pub, "carga");
    }
}