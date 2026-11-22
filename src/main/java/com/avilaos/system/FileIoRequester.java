package com.avilaos.system;

import com.avilaos.enums.IoOperation;
import com.avilaos.filesystem.FileOperation;
import com.avilaos.filesystem.FileOperationRequester;
import com.avilaos.model.ProcessControlBlock;

// Convierte lo que pidió la interfaz (FileOperation) en un proceso de E/S del computador. La interfaz no
// toca el disco ni el sistema de archivos: solo llega hasta aquí, y el proceso sigue el camino normal
// (Nuevo, Listo, CPU, solicitud al disco, Bloqueado por E/S, Listo, Terminado).
// Valores por omisión de los procesos que crea la interfaz (los del JSON del enunciado usan los mismos).
public class FileIoRequester implements FileOperationRequester {

    private static final int DEFAULT_MEMORY = 16;
    private static final int DEFAULT_DEADLINE = 500;
    private static final int DEFAULT_INSTRUCTIONS = 2;    // 1 de usuario + 1 de SO que pide el disco
    private static final int DEFAULT_PRIORITY = 1;
    private static final int MAX_NAME = 30;

    private final Cluster cluster;

    public FileIoRequester(Cluster cluster) {
        if (cluster == null) {
            throw new IllegalArgumentException("El solicitante necesita un clúster");
        }
        this.cluster = cluster;
    }

    @Override
    public void requestFileOperation(FileOperation operation) {
        int ram = cluster.getComputer(0).getKernel().getRam().getCapacity();
        ProcessControlBlock process = ProcessControlBlock.createFileIoProcess(nameFor(operation), 0,
                DEFAULT_PRIORITY, Math.min(DEFAULT_MEMORY, ram), DEFAULT_DEADLINE, DEFAULT_INSTRUCTIONS,
                operation.operation(), operation.path());
        process.setOwner(operation.owner());
        if (operation.operation() == IoOperation.UPDATE) {
            process.setNewName(operation.newName());
        } else if (operation.operation() == IoOperation.CREATE) {
            process.setCreateSpec(operation.createType(), operation.createBlocks(), operation.createPermission());
        }
        cluster.submit(process);
    }

    // Nombre del proceso: la operación y el último nombre de la ruta, p. ej. "READ boot.bin"
    private static String nameFor(FileOperation operation) {
        String path = operation.path();
        String name = operation.operation().name() + " " + path.substring(path.lastIndexOf('/') + 1);
        name = name.trim();
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }
}
