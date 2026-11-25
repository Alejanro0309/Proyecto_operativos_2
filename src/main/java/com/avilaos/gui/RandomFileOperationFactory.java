package com.avilaos.gui;

import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.UserMode;
import com.avilaos.filesystem.FileOperation;
import com.avilaos.filesystem.FileSystem;

// Genera operaciones de archivos al azar para probar rápido (Proyecto 2). Cada operación se comprueba
// ANTES con FileSystem.check... según el modo y el usuario activos (administrador, user1, user2...), así
// que nunca produce una que el sistema vaya a rechazar. Lo que hace según el modo:
//  - READ:   un archivo que el usuario puede leer (propio o público; el administrador, cualquiera).
//  - CREATE: un archivo de 1 a 8 bloques dentro de un directorio al azar donde se pueda crear
//            (el administrador, cualquiera; un usuario, solo los que le pertenecen).
//  - DELETE: solo archivos creados durante la corrida (no borra los de ejemplo) que pueda eliminar.
//  - UPDATE: renombra un archivo; solo el administrador.
//  - Directorios: solo el administrador (en modo usuario solo se crean archivos).
// Dentro de un lote no repite rutas ni reserva más bloques de los que quedan libres.
// Usa Math.random() porque java.util.Random no está permitido en el proyecto.
// Solo se usa desde el hilo de la interfaz: el estado del lote no está sincronizado.
public final class RandomFileOperationFactory {

    private static final int ATTEMPTS = 60;
    private static final int MAX_RANDOM_BLOCKS = 8;
    private static final int MAX_DIRECTORY_DEPTH = 3;
    private static final String[] EXTENSIONS = {".txt", ".dat", ".log", ".bin", ".png", ".csv"};
    private static final String LOADED_BY = "carga";

    private final FileSystem fileSystem;
    private final UserSession session;

    // estado de UN lote de operaciones
    private FileSystem.EntryInfo[] entries;
    private UserMode mode;
    private String user;
    private String owner;
    private String[] claimed;
    private int claimedCount;
    private int reservedBlocks;
    private int freeBlocks;

    public RandomFileOperationFactory(FileSystem fileSystem, UserSession session) {
        if (fileSystem == null || session == null) {
            throw new IllegalArgumentException("Hacen falta el sistema de archivos y la sesión");
        }
        this.fileSystem = fileSystem;
        this.session = session;
    }

    // Hasta 'count' operaciones válidas. Puede devolver menos (o ninguna) si no hay nada que se pueda hacer.
    public FileOperation[] generateFileOperations(int count) {
        startBatch(Math.max(1, count));
        FileOperation[] result = new FileOperation[count];
        int made = 0;
        for (int i = 0; i < count; i++) {
            FileOperation op = randomFileOperation();
            if (op != null) {
                result[made++] = op;
            }
        }
        FileOperation[] trimmed = new FileOperation[made];
        for (int i = 0; i < made; i++) {
            trimmed[i] = result[i];
        }
        return trimmed;
    }

    // Un directorio nuevo en un directorio al azar, o null si el modo activo no puede crear directorios.
    public FileOperation generateDirectory() {
        startBatch(1);
        if (mode != UserMode.ADMINISTRATOR) {
            return null;
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            FileSystem.EntryInfo parent = entries[rnd(0, entries.length - 1)];
            if (parent.type() != FileType.DIRECTORY || parent.depth() >= MAX_DIRECTORY_DEPTH) {
                continue;
            }
            String path = join(parent.path(), "dir" + rnd(100, 999));
            if (!isClaimed(path) && fileSystem.checkCreate(path, FileType.DIRECTORY, 0, mode, user) == null) {
                return new FileOperation(IoOperation.CREATE, path, FileType.DIRECTORY, 0,
                        FilePermission.PUBLIC, null, owner, mode);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- un lote

    private void startBatch(int size) {
        entries = fileSystem.snapshot();
        mode = session.getMode();
        user = mode == UserMode.USER ? session.getUser() : null;
        owner = mode == UserMode.USER ? user : "admin";
        claimed = new String[size];
        claimedCount = 0;
        reservedBlocks = 0;
        freeBlocks = fileSystem.freeBlocks();
    }

    private FileOperation randomFileOperation() {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int roll = rnd(0, 99);
            FileOperation op;
            if (roll < 40) {
                op = randomRead();
            } else if (roll < 75) {
                op = randomCreateFile();
            } else if (roll < 90) {
                op = randomDelete();
            } else {
                op = randomRename();
            }
            if (op != null) {
                claimed[claimedCount++] = op.path();
                if (op.operation() == IoOperation.CREATE) {
                    reservedBlocks += op.createBlocks();
                }
                return op;
            }
        }
        return null;
    }

    private FileOperation randomRead() {
        int[] candidates = new int[entries.length];
        int n = 0;
        for (int i = 0; i < entries.length; i++) {
            FileSystem.EntryInfo e = entries[i];
            if (e.type() == FileType.FILE && !isClaimed(e.path())
                    && fileSystem.checkRead(e.path(), mode, user) == null) {
                candidates[n++] = i;
            }
        }
        if (n == 0) {
            return null;
        }
        FileSystem.EntryInfo e = entries[candidates[rnd(0, n - 1)]];
        return new FileOperation(IoOperation.READ, e.path(), null, 0, null, null, owner, mode);
    }

    private FileOperation randomCreateFile() {
        int available = freeBlocks - reservedBlocks;
        if (available < 1) {
            return null;
        }
        // directorios donde este modo puede crear: todos (administrador) o los propios (usuario)
        int[] candidates = new int[entries.length];
        int n = 0;
        for (int i = 0; i < entries.length; i++) {
            FileSystem.EntryInfo e = entries[i];
            if (e.type() == FileType.DIRECTORY && (mode == UserMode.ADMINISTRATOR
                    || (user != null && user.equals(e.owner())))) {
                candidates[n++] = i;
            }
        }
        if (n == 0) {
            return null;
        }
        FileSystem.EntryInfo parent = entries[candidates[rnd(0, n - 1)]];
        String path = join(parent.path(), "arch" + rnd(100, 999) + EXTENSIONS[rnd(0, EXTENSIONS.length - 1)]);
        int blocks = rnd(1, Math.min(MAX_RANDOM_BLOCKS, available));
        FilePermission permission = rnd(0, 1) == 0 ? FilePermission.PUBLIC : FilePermission.PRIVATE;
        if (isClaimed(path) || fileSystem.checkCreate(path, FileType.FILE, blocks, mode, user) != null) {
            return null;
        }
        return new FileOperation(IoOperation.CREATE, path, FileType.FILE, blocks, permission, null, owner, mode);
    }

    // Solo archivos creados durante la corrida: así los archivos de ejemplo no desaparecen
    private FileOperation randomDelete() {
        int[] candidates = new int[entries.length];
        int n = 0;
        for (int i = 0; i < entries.length; i++) {
            FileSystem.EntryInfo e = entries[i];
            if (e.type() == FileType.FILE && !LOADED_BY.equals(e.creator()) && !isClaimed(e.path())
                    && fileSystem.checkDelete(e.path(), mode, user) == null) {
                candidates[n++] = i;
            }
        }
        if (n == 0) {
            return null;
        }
        FileSystem.EntryInfo e = entries[candidates[rnd(0, n - 1)]];
        return new FileOperation(IoOperation.DELETE, e.path(), null, 0, null, null, owner, mode);
    }

    // Solo el administrador renombra. Solo archivos: renombrar un directorio cambiaría las rutas de
    // las operaciones pendientes sobre lo que contiene.
    private FileOperation randomRename() {
        if (mode != UserMode.ADMINISTRATOR) {
            return null;
        }
        int[] candidates = new int[entries.length];
        int n = 0;
        for (int i = 0; i < entries.length; i++) {
            FileSystem.EntryInfo e = entries[i];
            if (e.type() == FileType.FILE && !isClaimed(e.path())) {
                candidates[n++] = i;
            }
        }
        if (n == 0) {
            return null;
        }
        FileSystem.EntryInfo e = entries[candidates[rnd(0, n - 1)]];
        int dot = e.name().lastIndexOf('.');
        String newName = "ren" + rnd(100, 999) + (dot >= 0 ? e.name().substring(dot) : "");
        if (fileSystem.checkUpdate(e.path(), newName, mode, user) != null) {
            return null;
        }
        return new FileOperation(IoOperation.UPDATE, e.path(), null, 0, null, newName, owner, mode);
    }

    // ---------------------------------------------------------------- auxiliares

    private boolean isClaimed(String path) {
        for (int i = 0; i < claimedCount; i++) {
            if (claimed[i].equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static String join(String directory, String name) {
        return directory.equals("/") ? "/" + name : directory + "/" + name;
    }

    private static int rnd(int min, int max) {
        return min + (int) (Math.random() * (max - min + 1));
    }
}