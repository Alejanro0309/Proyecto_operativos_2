package com.avilaos.config;

import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.SchedulingPolicy;

// Revisa que el JSON de procesos y configuración sea válido. Si algo falla lanza IllegalArgumentException
// con un mensaje que dice qué campo está mal y cuál es el rango.
// El quantum solo se exige (1 a 100) en Round Robin; en las demás políticas puede ser 0, como en el
// JSON del enunciado (FCFS con quantum 0).
public final class WorkloadValidator {

    private static final int MAX_NAME_LENGTH = 50;

    private WorkloadValidator() {
    }

    public static void validate(WorkloadConfig c) {
        if (c == null) {
            throw new IllegalArgumentException("El archivo de procesos está vacío");
        }
        if (c.globalConfig == null) {
            throw new IllegalArgumentException("Falta globalConfig");
        }
        range("Duración del ciclo (cycleDurationMs)", c.globalConfig.cycleDurationMs, 0, 10000);
        range("Tamaño del swap (swapSizeBlocks)", c.globalConfig.swapSizeBlocks, 0, 1000);
        if (c.globalConfig.headSpeed != 0) {
            range("Posiciones del cabezal por ciclo (headSpeed)", c.globalConfig.headSpeed, 1, 1000);
        }
        if (parseDiskPolicy(c.globalConfig.initialDiskPolicy) == null) {
            throw new IllegalArgumentException("initialDiskPolicy inválida '" + c.globalConfig.initialDiskPolicy
                    + "'. Válidas: FIFO, SSTF, SCAN, C_SCAN");
        }

        if (c.computer == null) {
            throw new IllegalArgumentException("Falta computer");
        }
        range("RAM del computador (ramCapacity)", c.computer.ramCapacity, 16, 1024);
        SchedulingPolicy policy = ConfigValidator.parsePolicy(c.computer.policy);
        if (policy == null) {
            throw new IllegalArgumentException("Política de CPU inválida '" + c.computer.policy
                    + "'. Válidas: FCFS, EDF, ROUND_ROBIN, PREEMPTIVE_PRIORITY");
        }
        range("Quantum", c.computer.quantum, policy == SchedulingPolicy.ROUND_ROBIN ? 1 : 0, 100);

        WorkloadConfig.ProcessEntry[] processes = c.processes == null ? new WorkloadConfig.ProcessEntry[0] : c.processes;
        for (int i = 0; i < processes.length; i++) {
            validateProcess(processes[i], i, c.computer.ramCapacity);
        }
    }

    private static void validateProcess(WorkloadConfig.ProcessEntry p, int i, int ram) {
        String label = "Proceso " + (i + 1);
        if (p == null) {
            throw new IllegalArgumentException(label + ": datos vacíos");
        }
        if (p.name == null || p.name.trim().isEmpty() || p.name.trim().length() > 30) {
            throw new IllegalArgumentException(label + ": el nombre no puede estar vacío ni pasar de 30 caracteres");
        }
        label = "Proceso '" + p.name + "'";
        range(label + " - memoria", p.memory, 1, ram);
        range(label + " - deadline", p.deadline, 1, 100000);
        range(label + " - instrucciones", p.instructions, 1, 10000);
        if (p.priority != null) {
            range(label + " - prioridad", p.priority, 0, 99);
        }
        IoOperation op = parseOperation(p.operation);
        if (op == null || !op.isFileOperation()) {
            throw new IllegalArgumentException(label + ": operation debe ser CREATE, READ, UPDATE o DELETE (recibido: '"
                    + p.operation + "')");
        }
        if (p.targetPath == null || !p.targetPath.trim().startsWith("/")) {
            throw new IllegalArgumentException(label + ": targetPath debe empezar con / (recibido: '" + p.targetPath + "')");
        }
        if (p.owner != null && p.owner.trim().isEmpty()) {
            throw new IllegalArgumentException(label + ": owner no puede estar vacío");
        }
        if (p.newName != null && (p.newName.trim().isEmpty() || p.newName.contains("/")
                || p.newName.length() > MAX_NAME_LENGTH)) {
            throw new IllegalArgumentException(label + ": newName no puede estar vacío, tener / ni pasar de "
                    + MAX_NAME_LENGTH + " caracteres");
        }
        if (p.permissions != null && FilePermission.fromText(p.permissions) == null) {
            throw new IllegalArgumentException(label + ": permissions debe ser 'public' o 'private'");
        }
        if (op == IoOperation.CREATE) {
            FileType type = p.createType == null ? FileType.FILE : FileSystemValidator.parseType(p.createType);
            if (type == null) {
                throw new IllegalArgumentException(label + ": createType debe ser FILE o DIRECTORY");
            }
            if (type == FileType.FILE) {
                range(label + " - fileSizeBlocks (tamaño del archivo a crear)", p.fileSizeBlocks, 1, 10000);
            }
        }
    }

    // Devuelven null si el texto no corresponde a ningún valor del enum
    public static DiskPolicy parseDiskPolicy(String text) {
        if (text == null) {
            return null;
        }
        try {
            return DiskPolicy.valueOf(text.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static IoOperation parseOperation(String text) {
        if (text == null) {
            return null;
        }
        try {
            return IoOperation.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void range(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " debe estar entre " + min + " y " + max
                    + " (recibido: " + value + ")");
        }
    }
}
