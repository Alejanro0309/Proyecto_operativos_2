/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.avilaos.tests;

import com.avilaos.disk.BlockMap;
import com.avilaos.disk.DiskCompletionListener;
import com.avilaos.disk.DiskRequest;
import com.avilaos.enums.BlockReason;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.ProcessState;
import com.avilaos.enums.SchedulingPolicy;
import com.avilaos.filesystem.FileSystem;
import com.avilaos.hardware.Disk;
import com.avilaos.kernel.EventLogger;
import com.avilaos.kernel.Kernel;
import com.avilaos.model.ProcessControlBlock;

/**
 * Procesos de E/S de archivos conectados al kernel (Proyecto 2, fase 3).
 *
 * Se arma lo mismo que el sistema real: un kernel, un disco y el sistema de
 * archivos del JSON del enunciado (200 bloques llenos). El reloj se simula a
 * mano: en cada ciclo corre el kernel y después el disco. Como el disco solo
 * considera solicitudes de ciclos anteriores y el kernel solo aplica avisos de
 * ciclos anteriores, el resultado es el mismo que con los hilos reales.
 *
 * Ejecutar en NetBeans: clic derecho sobre este archivo -> Run File (Shift+F6).
 *
 * @author SebMarval
 */
public class FileIoKernelTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testOfficialWorkloadFifo();
        testSlowHeadMissesDeadlines();
        testCpuRunsWhileWaitingForDisk();
        testPermissionRejectedBeforeDisk();
        testCreateNeedsFreeBlocks();
        testDeadlineCancelsDiskRequest();
        testFileDeletedWhileWaiting();
        testSingleInstructionProcess();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    // ================================================================ escenario

    /** Kernel + disco + sistema de archivos, conectados como en el sistema real. */
    private static final class Rig {
        final BlockMap map = new BlockMap(200, 100);
        final FileSystem fs = new FileSystem(map);
        final Disk disk;
        final Kernel kernel = new Kernel(0, 1024, SchedulingPolicy.FCFS, 3, EventLogger.SILENT);
        final int[] servedBlocks = new int[64];
        int servedCount = 0;
        int cycle = 0;

        Rig(int headSpeed) {
            loadOfficialFileSystem(fs);
            disk = new Disk(200, 50, headSpeed, DiskPolicy.FIFO);
            kernel.attachDisk(disk, fs);
            // registra el orden en que el disco atiende y le pasa el aviso al kernel
            disk.setCompletionListener(new DiskCompletionListener() {
                @Override
                public void onRequestCompleted(DiskRequest request, int when) {
                    servedBlocks[servedCount++] = request.getBlock();
                    kernel.onRequestCompleted(request, when);
                }
            });
        }

        void tick() {
            cycle++;
            kernel.runCycle(cycle);
            disk.onTick(cycle);
        }

        void runUntilIdle(int maxCycles) {
            while (cycle < maxCycles && kernel.getActiveProcessCount() > 0) {
                tick();
            }
        }

        String served() {
            String s = "";
            for (int i = 0; i < servedCount; i++) {
                s += (i == 0 ? "" : " ") + servedBlocks[i];
            }
            return s;
        }
    }

    /** El sistema de archivos del JSON del enunciado (versión actualizada: ocupa los 200 bloques). */
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

    /** Los 8 procesos del JSON del enunciado, en orden. */
    private static ProcessControlBlock[] officialWorkload() {
        ProcessControlBlock rename1 = io("Proc_34", IoOperation.UPDATE, "/usuarios/notas.txt");
        rename1.setNewName("notas_v2.txt");
        ProcessControlBlock rename2 = io("Proc_123", IoOperation.UPDATE, "/usuarios/data.csv");
        rename2.setNewName("data_v2.csv");
        return new ProcessControlBlock[] {
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

    // ================================================================ pruebas

    /** Caso P1 del enunciado: FCFS + FIFO atiende en el orden de llegada. */
    private static void testOfficialWorkloadFifo() {
        Rig r = new Rig(2);
        ProcessControlBlock[] ps = officialWorkload();
        for (ProcessControlBlock p : ps) {
            r.kernel.submit(p);
        }
        r.runUntilIdle(2000);
        check("P1 FIFO: orden 95 180 34 119 11 123 62 64 (obtenido: " + r.served() + ")",
                r.served().equals("95 180 34 119 11 123 62 64"));
        boolean allOk = true;
        for (ProcessControlBlock p : ps) {
            allOk &= p.getState() == ProcessState.TERMINATED && !p.isDeadlineMissed();
        }
        check("P1 FIFO: con cabezal a 2 posiciones/ciclo los 8 cumplen su deadline", allOk);
        check("UPDATE renombró notas.txt a notas_v2.txt",
                r.fs.exists("/usuarios/notas_v2.txt") && !r.fs.exists("/usuarios/notas.txt"));
        check("UPDATE renombró data.csv a data_v2.csv", r.fs.exists("/usuarios/data_v2.csv"));
        check("DELETE eliminó img1.png y liberó sus 4 bloques",
                !r.fs.exists("/usuarios/img1.png") && r.fs.freeBlocks() == 4);
        check("los procesos de E/S liberaron toda la RAM", r.kernel.getRam().getUsed() == 0);
    }

    /** Con 1 posición por ciclo, FIFO tarda tanto que se vencen deadlines de 500. */
    private static void testSlowHeadMissesDeadlines() {
        Rig r = new Rig(1);
        ProcessControlBlock[] ps = officialWorkload();
        for (ProcessControlBlock p : ps) {
            r.kernel.submit(p);
        }
        r.runUntilIdle(2000);
        int missed = 0;
        for (ProcessControlBlock p : ps) {
            missed += p.isDeadlineMissed() ? 1 : 0;
        }
        check("cabezal a 1 posición/ciclo: FIFO vence deadlines (" + missed + " de 8)", missed > 0);
        check("los vencidos no dejan solicitudes en la cola del disco", r.disk.snapshot().queue().length == 0);
    }

    /** Mientras un proceso espera al disco, la CPU atiende a otro. */
    private static void testCpuRunsWhileWaitingForDisk() {
        Rig r = new Rig(1);
        ProcessControlBlock reader = io("Lector", IoOperation.READ, "/sistema/config.sys");
        ProcessControlBlock cpu = new ProcessControlBlock("Calculo", 0,
                com.avilaos.enums.ProcessType.CPU_BOUND, 1, 16, 500, 10);
        r.kernel.submit(reader);
        r.kernel.submit(cpu);
        r.tick();   // ciclo 1: el lector pide el disco y se bloquea
        check("el proceso de E/S queda BLOCKED por DISK_IO",
                reader.getState() == ProcessState.BLOCKED && reader.getBlockReason() == BlockReason.DISK_IO);
        r.tick();   // ciclo 2: la CPU ya está con el otro proceso
        check("la CPU atiende a otro proceso mientras el disco trabaja", r.kernel.getCpu().getRunning() == cpu);
        r.runUntilIdle(500);
        check("el lector termina después de que el disco lo atiende",
                reader.getState() == ProcessState.TERMINATED && !reader.isDeadlineMissed());
    }

    /** Un usuario sin permiso: el proceso termina sin llegar al disco. */
    private static void testPermissionRejectedBeforeDisk() {
        Rig r = new Rig(2);
        ProcessControlBlock p = io("Intruso", IoOperation.DELETE, "/sistema/boot.bin");
        p.setOwner("user1");
        r.kernel.submit(p);
        r.runUntilIdle(50);
        check("user1 no puede borrar un archivo del sistema: el proceso termina",
                p.getState() == ProcessState.TERMINATED && r.fs.exists("/sistema/boot.bin"));
        check("la solicitud rechazada nunca llega al disco", r.disk.snapshot().served() == 0);
    }

    /** CREATE necesita bloques libres: con el disco lleno se rechaza; tras un DELETE, cabe. */
    private static void testCreateNeedsFreeBlocks() {
        Rig r = new Rig(2);
        ProcessControlBlock full = io("Crea1", IoOperation.CREATE, "/usuarios/nuevo.txt");
        full.setCreateSpec(FileType.FILE, 3, FilePermission.PUBLIC);
        r.kernel.submit(full);
        r.runUntilIdle(100);
        check("CREATE con el disco lleno se rechaza", !r.fs.exists("/usuarios/nuevo.txt"));

        ProcessControlBlock del = io("Borra", IoOperation.DELETE, "/usuarios/img1.png");
        ProcessControlBlock ok = io("Crea2", IoOperation.CREATE, "/usuarios/nuevo.txt");
        ok.setCreateSpec(FileType.FILE, 3, FilePermission.PUBLIC);
        r.kernel.submit(del);
        r.runUntilIdle(500);
        r.kernel.submit(ok);
        r.runUntilIdle(1000);
        FileSystem.EntryInfo info = r.fs.info("/usuarios/nuevo.txt");
        check("tras borrar img1.png, CREATE de 3 bloques empieza en el 119",
                info != null && info.startBlock() == 119 && info.blocks() == 3);
        check("el archivo nuevo registra al proceso que lo creó", info != null && info.creator().equals("P" + ok.getId()));

        ProcessControlBlock dir = io("CreaDir", IoOperation.CREATE, "/usuarios/fotos");
        dir.setCreateSpec(FileType.DIRECTORY, 0, FilePermission.PRIVATE);
        r.kernel.submit(dir);
        r.runUntilIdle(1500);
        check("CREATE de directorio no usa bloques", r.fs.exists("/usuarios/fotos") && r.fs.freeBlocks() == 1);
    }

    /** Si vence el deadline mientras espera, su solicitud sale de la cola del disco. */
    private static void testDeadlineCancelsDiskRequest() {
        Rig r = new Rig(1);
        ProcessControlBlock first = io("Largo", IoOperation.READ, "/usuarios/video.mp4");   // 130 de recorrido + 20
        ProcessControlBlock hurry = ProcessControlBlock.createFileIoProcess("Apurado", 0, 1, 16, 20, 2,
                IoOperation.READ, "/sistema/boot.bin");
        r.kernel.submit(first);
        r.kernel.submit(hurry);
        r.runUntilIdle(1000);
        check("el apurado vence su deadline esperando al disco", hurry.isDeadlineMissed());
        check("el disco solo atendió al que no venció (" + r.served() + ")", r.served().equals("180"));
    }

    /** El archivo se borra mientras otro proceso espera para leerlo. */
    private static void testFileDeletedWhileWaiting() {
        Rig r = new Rig(2);
        ProcessControlBlock del = io("Borra", IoOperation.DELETE, "/usuarios/img1.png");
        ProcessControlBlock read = io("Lee", IoOperation.READ, "/usuarios/img1.png");
        r.kernel.submit(del);
        r.kernel.submit(read);
        r.runUntilIdle(500);
        check("READ de un archivo borrado mientras esperaba termina sin error del sistema",
                read.getState() == ProcessState.TERMINATED && !r.fs.exists("/usuarios/img1.png"));
    }

    /** Un proceso de 1 instrucción no vuelve a la CPU: termina al completar el disco. */
    private static void testSingleInstructionProcess() {
        Rig r = new Rig(2);
        ProcessControlBlock p = ProcessControlBlock.createFileIoProcess("Uno", 0, 1, 16, 500, 1,
                IoOperation.READ, "/sistema/script.py");
        r.kernel.submit(p);
        r.tick();
        check("1 instrucción: espera al disco en vez de terminar", p.getState() == ProcessState.BLOCKED);
        r.runUntilIdle(200);
        check("1 instrucción: termina cuando el disco lo atiende",
                p.getState() == ProcessState.TERMINATED && !p.isDeadlineMissed() && r.disk.snapshot().served() == 1);
    }

    // ================================================================ utilidades

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
