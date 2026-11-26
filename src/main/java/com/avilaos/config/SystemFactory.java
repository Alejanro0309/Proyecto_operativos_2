package com.avilaos.config;

import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.SchedulingPolicy;
import com.avilaos.hardware.Disk;
import com.avilaos.kernel.EventLogger;
import com.avilaos.model.ProcessControlBlock;
import com.avilaos.model.ProcessIdGenerator;
import com.avilaos.system.Cluster;

// Arma el sistema del Proyecto 2 a partir de los dos JSON ya cargados: un computador, el disco (con su
// política y su cabezal) y el sistema de archivos, y deja en el computador los procesos iniciales.
// El reloj queda sin arrancar: eso lo decide la GUI con cluster.start().
public final class SystemFactory {

    // El JSON no dice cuántas posiciones recorre el cabezal por ciclo: este es el valor por omisión
    public static final int DEFAULT_HEAD_SPEED = 10;
    private static final int DEFAULT_PRIORITY = 1;

    private SystemFactory() {
    }

    public static Cluster build(FileSystemLoader.LoadedFileSystem state, WorkloadConfig cfg, EventLogger logger) {
        WorkloadValidator.validate(cfg);

        // cada simulación nueva empieza los IDs de proceso desde 1
        ProcessIdGenerator.reset();

        DiskPolicy diskPolicy = WorkloadValidator.parseDiskPolicy(cfg.globalConfig.initialDiskPolicy);
        int speed = cfg.globalConfig.headSpeed > 0 ? cfg.globalConfig.headSpeed : DEFAULT_HEAD_SPEED;
        Disk disk = new Disk(state.totalBlocks(), state.initialHead(), speed, diskPolicy);
        disk.setLogger(logger);

        SchedulingPolicy cpuPolicy = ConfigValidator.parsePolicy(cfg.computer.policy);
        Cluster cluster = new Cluster(cfg.computer.ramCapacity, cpuPolicy, cfg.computer.quantum,
                cfg.globalConfig.cycleDurationMs, disk, state.fileSystem(), logger);

        WorkloadConfig.ProcessEntry[] processes = cfg.processes == null ? new WorkloadConfig.ProcessEntry[0] : cfg.processes;
        for (int i = 0; i < processes.length; i++) {
            cluster.submit(buildProcess(processes[i]));
        }
        return cluster;
    }

    private static ProcessControlBlock buildProcess(WorkloadConfig.ProcessEntry p) {
        IoOperation op = WorkloadValidator.parseOperation(p.operation);
        int priority = p.priority == null ? DEFAULT_PRIORITY : p.priority;
        ProcessControlBlock process = ProcessControlBlock.createFileIoProcess(p.name, 0, priority, p.memory,
                p.deadline, p.instructions, op, p.targetPath);
        if (p.owner != null) {
            process.setOwner(p.owner);
        }
        if (op == IoOperation.UPDATE) {
            process.setNewName(p.newName != null ? p.newName : defaultNewName(p.targetPath));
        } else if (op == IoOperation.CREATE) {
            FileType type = p.createType == null ? FileType.FILE : FileSystemValidator.parseType(p.createType);
            FilePermission permission = p.permissions == null ? FilePermission.PUBLIC : FilePermission.fromText(p.permissions);
            process.setCreateSpec(type, p.fileSizeBlocks, permission);
        }
        return process;
    }

    // El JSON del enunciado no trae el nombre nuevo de los UPDATE: se usa el viejo con _v2 antes de la
    // extensión (notas.txt pasa a notas_v2.txt), igual que en las pruebas del kernel.
    static String defaultNewName(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) + "_v2" + base.substring(dot) : base + "_v2";
    }
}
