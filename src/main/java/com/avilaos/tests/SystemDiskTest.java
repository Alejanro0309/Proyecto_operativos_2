/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.avilaos.tests;

import com.avilaos.disk.BlockMap;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.SchedulingPolicy;
import com.avilaos.filesystem.FileSystem;
import com.avilaos.hardware.Disk;
import com.avilaos.kernel.EventLogger;
import com.avilaos.model.ProcessControlBlock;
import com.avilaos.system.Cluster;

/**
 * El sistema del Proyecto 2 con HILOS REALES: un computador y el disco, cada
 * uno en su hilo, sincronizados por el reloj global.
 *
 * La prueba clave es el determinismo: la misma carga se corre con los hilos
 * reales (ciclo de 0 ms) y con ticks manuales, y cada proceso debe terminar en
 * EXACTAMENTE el mismo ciclo. Así se comprueba que el orden en que corren los
 * hilos del kernel y del disco dentro de un ciclo no cambia el resultado.
 *
 * Ejecutar en NetBeans: clic derecho sobre este archivo -> Run File (Shift+F6).
 *
 * @author SebMarval
 */
public class SystemDiskTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws InterruptedException {
        testWiring();
        testDiskRunsInItsOwnThread();
        testThreadsMatchManualTicks();
        testPauseStopsDisk();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    // ================================================================ escenario

    private static Cluster newSystem(int cycleMs, int headSpeed) {
        FileSystem fs = new FileSystem(new BlockMap(200, 100));
        loadOfficialFileSystem(fs);
        Disk disk = new Disk(200, 50, headSpeed, DiskPolicy.FIFO);
        return new Cluster(1024, SchedulingPolicy.FCFS, 3, cycleMs, disk, fs, EventLogger.SILENT);
    }

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

    private static ProcessControlBlock io(String name, IoOperation op, String path) {
        return ProcessControlBlock.createFileIoProcess(name, 0, 1, 16, 500, 2, op, path);
    }

    /** Los 8 procesos del JSON del enunciado. */
    private static ProcessControlBlock[] officialWorkload() {
        ProcessControlBlock rename1 = io("Proc_34", IoOperation.UPDATE, "/usuarios/notas.txt");
        rename1.setNewName("notas_v2.txt");
        ProcessControlBlock rename2 = io("Proc_123", IoOperation.UPDATE, "/usuarios/data.csv");
        rename2.setNewName("data_v2.csv");
        return new ProcessControlBlock[]{
            io("Proc_95", IoOperation.READ, "/sistema/config.sys"),
            io("Proc_180", IoOperation.READ, "/usuarios/video.mp4"),
            rename1,
            io("Proc_119", IoOperation.DELETE, "/usuarios/img1.png"),
            io("Proc_11", IoOperation.READ, "/sistema/boot.bin"),
            rename2,
            io("Proc_62", IoOperation.READ, "/sistema/script.py"),
            io("Proc_64", IoOperation.READ, "/sistema/core.dll"),
        };
    }

    private static boolean waitUntilIdle(Cluster c, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (c.getComputer(0).getKernel().getActiveProcessCount() == 0) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    // ================================================================ pruebas

    private static void testWiring() {
        Cluster c = newSystem(0, 2);
        check("el sistema del Proyecto 2 tiene 1 computador", c.getComputerCount() == 1);
        check("el reloj tiene 2 componentes: el computador y el disco", c.getClock().getComponentCount() == 2);
        check("el clúster expone el disco y el sistema de archivos", c.getDisk() != null && c.getFileSystem() != null);
        check("sin disco no se puede crear el sistema", throwsIAE(() ->
                new Cluster(1024, SchedulingPolicy.FCFS, 3, 0, null, null, EventLogger.SILENT)));
    }

    private static void testDiskRunsInItsOwnThread() throws InterruptedException {
        Cluster c = newSystem(5, 2);
        c.start();
        Thread.sleep(100);
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Thread[] threads = new Thread[root.activeCount() * 2 + 16];
        int count = root.enumerate(threads, true);
        boolean diskThread = false;
        boolean computerThread = false;
        for (int i = 0; i < count; i++) {
            if (threads[i] != null) {
                diskThread |= threads[i].getName().equals("Disk");
                computerThread |= threads[i].getName().equals("Computer-0");
            }
        }
        c.stop();
        check("el disco corre en su propio hilo (\"Disk\")", diskThread);
        check("el computador corre en su hilo (\"Computer-0\")", computerThread);
    }

    /** Hilos reales y ticks manuales dan los mismos ciclos de fin: el sistema es determinista. */
    private static void testThreadsMatchManualTicks() throws InterruptedException {
        Cluster manual = newSystem(0, 2);
        ProcessControlBlock[] a = officialWorkload();
        for (ProcessControlBlock p : a) {
            manual.submit(p);
        }
        int cycle = 0;
        while (cycle < 3000 && manual.getComputer(0).getKernel().getActiveProcessCount() > 0) {
            cycle++;
            manual.getComputer(0).onTick(cycle);
            manual.getDisk().onTick(cycle);
        }

        boolean allSame = true;
        for (int round = 0; round < 5; round++) {
            Cluster threaded = newSystem(0, 2);
            ProcessControlBlock[] b = officialWorkload();
            for (ProcessControlBlock p : b) {
                threaded.submit(p);
            }
            threaded.start();
            boolean done = waitUntilIdle(threaded, 10000);
            threaded.stop();
            for (int i = 0; i < a.length && done; i++) {
                allSame &= a[i].getFinishCycle() == b[i].getFinishCycle();
            }
            allSame &= done && threaded.getFileSystem().exists("/usuarios/notas_v2.txt")
                    && !threaded.getFileSystem().exists("/usuarios/img1.png");
        }
        check("con hilos reales cada proceso termina en el mismo ciclo que con ticks manuales (5 corridas)", allSame);
        check("FIFO con cabezal a 2: los 8 procesos del enunciado terminan (último en el ciclo "
                + a[a.length - 1].getFinishCycle() + ")", manual.getComputer(0).getKernel().getActiveProcessCount() == 0);
    }

    private static void testPauseStopsDisk() throws InterruptedException {
        Cluster c = newSystem(2, 1);
        c.submit(io("Lector", IoOperation.READ, "/usuarios/video.mp4"));
        c.start();
        Thread.sleep(80);
        c.pause();
        Thread.sleep(40);
        int head = c.getDisk().snapshot().headPosition();
        int cyc = c.getCurrentCycle();
        Thread.sleep(80);
        boolean frozen = c.getDisk().snapshot().headPosition() == head && c.getCurrentCycle() == cyc;
        c.resume();
        Thread.sleep(80);
        boolean moved = c.getDisk().snapshot().headPosition() != head;
        c.stop();
        check("en pausa el cabezal no se mueve", frozen);
        check("al reanudar el cabezal sigue su recorrido", moved);
    }

    // ================================================================ utilidades

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