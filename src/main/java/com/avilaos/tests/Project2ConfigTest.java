package com.avilaos.tests;

import com.avilaos.config.FileSystemConfig;
import com.avilaos.config.FileSystemLoader;
import com.avilaos.config.FileSystemValidator;
import com.avilaos.config.SystemFactory;
import com.avilaos.config.WorkloadConfig;
import com.avilaos.config.WorkloadLoader;
import com.avilaos.config.WorkloadValidator;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.FilePermission;
import com.avilaos.kernel.EventLogger;
import com.avilaos.kernel.Kernel;
import com.avilaos.system.Cluster;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

// Prueba de la carga de los dos JSON del Proyecto 2 y del sistema armado con ellos:
//  - el JSON del sistema de archivos: cargar, validar, construir, guardar y volver a cargar (ida y vuelta,
//    incluso con archivos cuyos bloques ya no son consecutivos);
//  - el JSON de procesos: validaciones (el quantum 0 vale si la política no es Round Robin);
//  - integración: con los JSON de ejemplo de config/proyecto2 se arma el sistema y se corre con reloj manual.
public class Project2ConfigTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        testFileSystemBuild();
        testRoundTrip();
        testFileSystemRejects();
        testWorkloadRejects();
        testBadFiles();
        testSampleFiles();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    // ================================================================ sistema de archivos

    // El JSON del enunciado: 9 archivos que llenan los 200 bloques
    private static FileSystemConfig enunciadoConfig() {
        FileSystemConfig c = new FileSystemConfig();
        c.diskConfig = new FileSystemConfig.DiskSettings();
        c.diskConfig.totalBlocks = 200;
        c.diskConfig.initialHead = 50;
        c.fileSystem = new FileSystemConfig.Entry[]{
            entry("/mbr.bin", "FILE", 0, 11, "admin", "public"),
            entry("/sistema", "DIRECTORY", 0, 0, "admin", "public"),
            entry("/usuarios", "DIRECTORY", 0, 0, "admin", "public"),
            entry("/sistema/boot.bin", "FILE", 11, 23, "admin", "public"),
            entry("/usuarios/notas.txt", "FILE", 34, 28, "user1", "private"),
            entry("/sistema/script.py", "FILE", 62, 2, "admin", "public"),
            entry("/sistema/core.dll", "FILE", 64, 31, "admin", "public"),
            entry("/sistema/config.sys", "FILE", 95, 24, "admin", "public"),
            entry("/usuarios/img1.png", "FILE", 119, 4, "user1", "private"),
            entry("/usuarios/data.csv", "FILE", 123, 57, "user2", "public"),
            entry("/usuarios/video.mp4", "FILE", 180, 20, "user1", "public")};
        return c;
    }

    // Un disco chico para probar rechazos: un directorio y un archivo
    private static FileSystemConfig small() {
        FileSystemConfig c = new FileSystemConfig();
        c.diskConfig = new FileSystemConfig.DiskSettings();
        c.diskConfig.totalBlocks = 100;
        c.diskConfig.initialHead = 0;
        c.fileSystem = new FileSystemConfig.Entry[]{
            entry("/a", "DIRECTORY", 0, 0, "admin", "public"),
            entry("/a/f.txt", "FILE", 0, 5, "admin", "public")};
        return c;
    }

    private static FileSystemConfig.Entry entry(String path, String type, int start, int blocks, String owner, String permissions) {
        FileSystemConfig.Entry e = new FileSystemConfig.Entry();
        e.path = path;
        e.type = type;
        if (type.equals("FILE")) {
            e.startBlock = start;
            e.blocks = blocks;
        }
        e.owner = owner;
        e.permissions = permissions;
        return e;
    }

    private static void testFileSystemBuild() {
        check("el JSON del enunciado es válido", !rejects(enunciadoConfig()));
        FileSystemLoader.LoadedFileSystem st = FileSystemLoader.build(enunciadoConfig(), 100);
        check("se construyen las 12 entradas (raíz, 2 directorios, 9 archivos)", st.fileSystem().snapshot().length == 12);
        check("el disco del enunciado queda lleno y el swap aparte y libre",
                st.fileSystem().freeBlocks() == 0 && st.blockMap().freeSwapBlocks() == 100);
        check("los datos del disco: 200 bloques y cabezal en 50", st.totalBlocks() == 200 && st.initialHead() == 50);
        check("boot.bin: primer bloque 11 y 23 bloques", st.fileSystem().info("/sistema/boot.bin").startBlock() == 11
                && st.fileSystem().info("/sistema/boot.bin").blocks() == 23);
        check("los permisos y dueños se conservan", st.fileSystem().info("/usuarios/notas.txt").permission() == FilePermission.PRIVATE
                && st.fileSystem().info("/usuarios/notas.txt").owner().equals("user1"));

        // los directorios se crean antes que sus archivos aunque vengan después en el JSON
        FileSystemConfig reversed = small();
        FileSystemConfig.Entry first = reversed.fileSystem[0];
        reversed.fileSystem[0] = reversed.fileSystem[1];
        reversed.fileSystem[1] = first;
        check("el orden del JSON no importa: un archivo antes que su directorio también carga",
                FileSystemLoader.build(reversed, 0).fileSystem().exists("/a/f.txt"));
    }

    // Guardar y volver a cargar debe dar el mismo estado, incluso con una cadena de bloques NO consecutiva
    private static void testRoundTrip() throws IOException {
        FileSystemLoader.LoadedFileSystem st = FileSystemLoader.build(enunciadoConfig(), 0);
        st.fileSystem().delete("/usuarios/img1.png");        // libera 119 a 122
        st.fileSystem().delete("/sistema/script.py");        // libera 62 y 63
        st.fileSystem().createFile("/usuarios/nuevo.bin", 6, "user1", FilePermission.PRIVATE, "P9");
        check("el archivo nuevo ocupa los dos huecos: 62, 63 y 119 a 122",
                same(st.blockMap().chainOf(62), 62, 63, 119, 120, 121, 122));

        File file = File.createTempFile("avilaos-fs", ".json");
        file.deleteOnExit();
        FileSystemLoader.save(st.fileSystem(), 200, 77, file);
        FileSystemConfig back = FileSystemLoader.load(file);
        check("lo guardado es válido", !rejects(back));
        FileSystemLoader.LoadedFileSystem again = FileSystemLoader.build(back, 0);
        check("ida y vuelta: el cabezal queda donde se guardó", again.initialHead() == 77);
        check("ida y vuelta: 11 entradas (12 - 2 borrados + 1 nuevo)", again.fileSystem().snapshot().length == 11);
        check("ida y vuelta: la cadena no consecutiva se conserva",
                same(again.blockMap().chainOf(62), 62, 63, 119, 120, 121, 122)
                && again.fileSystem().info("/usuarios/nuevo.bin").blocks() == 6);
        check("ida y vuelta: dueño y permiso se conservan", again.fileSystem().info("/usuarios/nuevo.bin").owner().equals("user1")
                && again.fileSystem().info("/usuarios/nuevo.bin").permission() == FilePermission.PRIVATE);
        check("ida y vuelta: lo borrado sigue borrado y el disco sigue lleno",
                !again.fileSystem().exists("/usuarios/img1.png") && again.fileSystem().freeBlocks() == 0);
        check("guardar con un cabezal fuera del disco se rechaza", throwsIAE(
                () -> FileSystemLoader.save(st.fileSystem(), 200, 200, file)));
    }

    private static void testFileSystemRejects() {
        check("un disco de 50 bloques (menos del mínimo) se rechaza", rejects(withTotal(50)));
        check("un disco de 600 bloques (más del máximo) se rechaza", rejects(withTotal(600)));
        FileSystemConfig c = small();
        c.diskConfig.initialHead = 100;
        check("un cabezal fuera del disco se rechaza", rejects(c));
        c = small();
        c.diskConfig = null;
        check("sin diskConfig se rechaza", rejects(c));

        c = enunciadoConfig();
        c.fileSystem[10].startBlock = 170;                   // video.mp4 pisaría a data.csv (123 a 179)
        check("dos archivos con el mismo bloque se rechazan", rejects(c));
        c = small();
        c.fileSystem[1].type = "XYZ";
        check("un tipo inválido se rechaza", rejects(c));
        c = small();
        c.fileSystem[1].permissions = "secreto";
        check("un permiso inválido se rechaza", rejects(c));
        c = small();
        c.fileSystem[1].owner = " ";
        check("un dueño vacío se rechaza", rejects(c));
        c = small();
        c.fileSystem[1].path = "a/f.txt";
        check("una ruta sin / inicial se rechaza", rejects(c));
        c = small();
        c.fileSystem[1].path = "/b/g.txt";
        check("un archivo cuyo directorio no está en el JSON se rechaza", rejects(c));
        c = small();
        c.fileSystem[1].startBlock = 98;
        check("un archivo que se sale del disco se rechaza", rejects(c));
        c = small();
        c.fileSystem[1].blocks = 0;
        check("un archivo de 0 bloques se rechaza", rejects(c));
        c = small();
        c.fileSystem = new FileSystemConfig.Entry[]{c.fileSystem[0], c.fileSystem[1], entry("/a/f.txt", "FILE", 10, 2, "admin", "public")};
        check("una ruta repetida se rechaza", rejects(c));
        c = small();
        c.fileSystem[1].chain = new int[]{1, 2};             // dice 5 bloques y la cadena tiene 2
        check("una cadena que no coincide con blocks se rechaza", rejects(c));
        check("construir con un JSON inválido también lanza el error", throwsIAE(() -> FileSystemLoader.build(withTotal(50), 0)));
    }

    private static FileSystemConfig withTotal(int total) {
        FileSystemConfig c = small();
        c.diskConfig.totalBlocks = total;
        return c;
    }

    // ================================================================ procesos y configuración

    private static WorkloadConfig workload() {
        WorkloadConfig w = new WorkloadConfig();
        w.globalConfig = new WorkloadConfig.GlobalSettings();
        w.globalConfig.cycleDurationMs = 300;
        w.globalConfig.swapSizeBlocks = 100;
        w.globalConfig.initialDiskPolicy = "FIFO";
        w.computer = new WorkloadConfig.ComputerSettings();
        w.computer.ramCapacity = 1024;
        w.computer.policy = "FCFS";
        w.computer.quantum = 0;
        w.processes = new WorkloadConfig.ProcessEntry[]{process("P_leer", "/sistema/boot.bin", "READ")};
        return w;
    }

    private static WorkloadConfig.ProcessEntry process(String name, String path, String operation) {
        WorkloadConfig.ProcessEntry p = new WorkloadConfig.ProcessEntry();
        p.name = name;
        p.memory = 16;
        p.deadline = 500;
        p.instructions = 2;
        p.targetPath = path;
        p.operation = operation;
        return p;
    }

    private static void testWorkloadRejects() {
        check("el JSON de procesos del enunciado es válido (FCFS con quantum 0)", !rejects(workload()));
        WorkloadConfig w = workload();
        w.computer.policy = "ROUND_ROBIN";
        check("Round Robin con quantum 0 se rechaza", rejects(w));
        w.computer.quantum = 3;
        check("Round Robin con quantum 3 es válido", !rejects(w));
        w = workload();
        w.globalConfig.initialDiskPolicy = "XYZ";
        check("una política de disco inválida se rechaza", rejects(w));
        check("C-SCAN con guion se acepta como C_SCAN", WorkloadValidator.parseDiskPolicy("C-SCAN") == DiskPolicy.C_SCAN);
        w = workload();
        w.computer.policy = "XYZ";
        check("una política de CPU inválida se rechaza", rejects(w));
        w = workload();
        w.computer.ramCapacity = 8;
        check("una RAM menor que 16 se rechaza", rejects(w));
        w = workload();
        w.processes[0].memory = 2000;
        check("un proceso que pide más memoria que la RAM se rechaza", rejects(w));
        w = workload();
        w.processes[0].operation = "SUSPEND";
        check("SUSPEND no es una operación de archivos válida", rejects(w));
        w = workload();
        w.processes[0].operation = "COPIAR";
        check("una operación desconocida se rechaza", rejects(w));
        w = workload();
        w.processes[0].targetPath = "boot.bin";
        check("una ruta sin / inicial se rechaza", rejects(w));
        w = workload();
        w.processes[0].name = " ";
        check("un nombre vacío se rechaza", rejects(w));
        w = workload();
        w.processes[0].operation = "CREATE";
        check("CREATE de un archivo sin tamaño se rechaza", rejects(w));
        w.processes[0].fileSizeBlocks = 3;
        check("CREATE con fileSizeBlocks es válido", !rejects(w));
        w = workload();
        w.globalConfig.swapSizeBlocks = -1;
        check("un swap negativo se rechaza", rejects(w));
        w = workload();
        w.globalConfig.cycleDurationMs = 20000;
        check("un ciclo de más de 10000 ms se rechaza", rejects(w));
    }

    private static void testBadFiles() throws IOException {
        check("un JSON mal formado se rechaza (sistema de archivos)", loadFails(tempFile("{ esto no es json"), true));
        check("un archivo vacío se rechaza (sistema de archivos)", loadFails(tempFile(""), true));
        check("un archivo que no existe se rechaza", loadFails(new File("no-existe-12345.json"), true));
        check("un JSON mal formado se rechaza (procesos)", loadFails(tempFile("{ esto no es json"), false));
        check("texto en un campo numérico se rechaza (procesos)",
                loadFails(tempFile("{\"globalConfig\": {\"cycleDurationMs\": \"rapido\"}}"), false));
        check("un elemento null en la lista de procesos se rechaza",
                loadFails(tempFile("{\"processes\": [null]}"), false));
    }

    // ================================================================ integración con los JSON de ejemplo

    private static void testSampleFiles() {
       File fsFile = new File("config/escenarios_2/sistema_archivos.json");
        File procFile = new File("config/escenarios_2/procesos.json");
        File suspFile = new File("config/escenarios_2/procesos_suspension.json");
        if (!fsFile.exists() || !procFile.exists() || !suspFile.exists()) {
            System.out.println("[OMITIDA] faltan los JSON de ejemplo en config/escenarios_2 (se corre desde la carpeta del proyecto)");
            return;
        }
        FileSystemConfig fsCfg = FileSystemLoader.load(fsFile);
        check("el JSON de ejemplo del sistema de archivos es válido", !rejects(fsCfg));

        // los 8 procesos del enunciado con FIFO: todos terminan y los cambios quedan aplicados
        WorkloadConfig workloadCfg = WorkloadLoader.load(procFile);
        check("el JSON de ejemplo de procesos es válido", !rejects(workloadCfg));
        Cluster cluster = SystemFactory.build(FileSystemLoader.build(fsCfg, workloadCfg.globalConfig.swapSizeBlocks),
                workloadCfg, EventLogger.SILENT);
        Kernel kernel = cluster.getComputer(0).getKernel();
        int cycles = runManually(cluster, 3000);
        evidence("enunciado: terminaron en el ciclo " + cycles + "; el disco atendió " + cluster.getDisk().snapshot().served());
        check("los 8 procesos del enunciado terminan sin vencer deadline",
                kernel.getTerminatedSnapshot().length == 8 && kernel.getDeadlineMissedCount() == 0);
        check("el disco atendió las 8 solicitudes con FIFO",
                cluster.getDisk().snapshot().served() == 8
                && cluster.getDisk().snapshot().servedPerPolicy()[DiskPolicy.FIFO.ordinal()] == 8);
        check("los UPDATE renombraron (notas_v2.txt y data_v2.csv) y el DELETE borró img1.png",
                cluster.getFileSystem().exists("/usuarios/notas_v2.txt") && cluster.getFileSystem().exists("/usuarios/data_v2.csv")
                && !cluster.getFileSystem().exists("/usuarios/img1.png"));
        check("el DELETE liberó los 4 bloques de img1.png", cluster.getFileSystem().freeBlocks() == 4);

        // RAM de 48 con procesos de 16: el planificador a mediano plazo tiene que suspender y reactivar
        WorkloadConfig suspension = WorkloadLoader.load(suspFile);
        check("el JSON de ejemplo de suspensión es válido", !rejects(suspension));
        Cluster small = SystemFactory.build(FileSystemLoader.build(fsCfg, suspension.globalConfig.swapSizeBlocks),
                suspension, EventLogger.SILENT);
        Kernel smallKernel = small.getComputer(0).getKernel();
        int smallCycles = runManually(small, 5000);
        evidence("suspensión: terminaron en el ciclo " + smallCycles + "; suspensiones " + smallKernel.getSuspensionCount()
                + ", reactivaciones " + smallKernel.getReactivationCount());
        check("con RAM 48 se suspende al menos un proceso", smallKernel.getSuspensionCount() >= 1);
        check("y los suspendidos se reactivan", smallKernel.getReactivationCount() >= 1);
        check("los 6 procesos terminan igual y la RAM queda libre",
                smallKernel.getTerminatedSnapshot().length == 6 && smallKernel.getRam().getUsed() == 0);
    }

    // Reloj manual: en cada ciclo corre el computador y después el disco (como hace el reloj con hilos)
    private static int runManually(Cluster cluster, int maxCycles) {
        int cycle = 0;
        while (cycle < maxCycles && cluster.getComputer(0).getKernel().getActiveProcessCount() > 0) {
            cycle++;
            cluster.getComputer(0).onTick(cycle);
            cluster.getDisk().onTick(cycle);
        }
        return cycle;
    }

    // ---------------------------------------------------------------- auxiliares

    private static File tempFile(String content) throws IOException {
        File f = File.createTempFile("avilaos-p2", ".json");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
        }
        return f;
    }

    private static boolean loadFails(File f, boolean fileSystem) {
        try {
            if (fileSystem) {
                FileSystemLoader.load(f);
            } else {
                WorkloadLoader.load(f);
            }
            return false;
        } catch (IllegalArgumentException e) {
            return e.getMessage().startsWith("Archivo inválido");
        }
    }

    private static boolean rejects(FileSystemConfig c) {
        try {
            FileSystemValidator.validate(c);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean rejects(WorkloadConfig w) {
        try {
            WorkloadValidator.validate(w);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean same(int[] actual, int... expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean throwsIAE(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void evidence(String text) {
        System.out.println("   · " + text);
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
