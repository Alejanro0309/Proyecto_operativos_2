package com.avilaos.filesystem;

import com.avilaos.disk.BlockMap;
import com.avilaos.enums.BlockOwnerType;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.enums.UserMode;
import java.util.concurrent.Semaphore;

// Sistema de archivos jerárquico sobre el disco. Guarda el árbol de directorios y archivos, y pide
// los bloques a BlockMap (asignación encadenada).
//
// Dos grupos de métodos:
//  - check...: preguntan si una operación está permitida (permisos y estado actual) y devuelven
//    null si sí, o el MOTIVO del rechazo. La interfaz los usa al crear el proceso de E/S, para
//    rechazar sin interrumpir la simulación y explicar por qué.
//  - create / loadFile / delete / rename: aplican el cambio. Los llama el proceso de E/S cuando el
//    disco termina de atenderlo. Vuelven a comprobar el estado (puede haber cambiado desde que se
//    creó el proceso) y lanzan IllegalArgumentException con el motivo si ya no se puede.
//
// Un Semaphore protege el árbol: lo usan el hilo del kernel (procesos de E/S) y el de la interfaz.
// El semáforo no es reentrante: los métodos públicos lo toman una vez y llaman a métodos privados
// que no lo toman. Orden de bloqueo: primero el de FileSystem y dentro el de BlockMap, nunca al revés.
public class FileSystem {

    private static final int MAX_NAME_LENGTH = 50;

    private final BlockMap blockMap;
    private final FsNode root = new FsNode("", FileType.DIRECTORY, "admin", FilePermission.PUBLIC, -1, 0, "sistema");
    private final Semaphore lock = new Semaphore(1);

    // Copia inmutable de una entrada, para el JTree y la tabla de asignación.
    public record EntryInfo(String path, String name, FileType type, String owner, FilePermission permission,
            int startBlock, int blocks, String creator, int depth) {
    }

    public FileSystem(BlockMap blockMap) {
        if (blockMap == null) {
            throw new IllegalArgumentException("El sistema de archivos necesita un mapa de bloques");
        }
        this.blockMap = blockMap;
    }

    // ================================================================ comprobar permisos y estado

    // Crear un archivo o directorio. 'blocks' solo cuenta si type es FILE.
    public String checkCreate(String path, FileType type, int blocks, UserMode mode, String user) {
        lock.acquireUninterruptibly();
        try {
            String[] parts = split(path);
            if (parts == null || parts.length == 0) {
                return "La ruta '" + path + "' no es válida: debe empezar con / y no tener nombres vacíos";
            }
            String parentPath = parentPath(path);
            FsNode parent = findNode(parentPath);
            if (parent == null) {
                return "No existe el directorio '" + parentPath + "'";
            }
            if (!parent.isDirectory()) {
                return "'" + parentPath + "' es un archivo, no un directorio";
            }
            if (parent.findChild(baseName(path)) != null) {
                return "Ya existe '" + path + "'";
            }
            if (mode == UserMode.USER) {
                String identity = missingIdentity(user);
                if (identity != null) {
                    return identity;
                }
                if (type != FileType.FILE) {
                    return "En modo usuario solo se pueden crear archivos, no directorios";
                }
                if (!user.equals(parent.getOwner())) {
                    return "Solo se puede crear dentro de directorios propios: '" + parentPath
                            + "' pertenece a '" + parent.getOwner() + "'";
                }
            }
            if (type == FileType.FILE) {
                if (blocks < 1) {
                    return "Un archivo debe tener al menos 1 bloque";
                }
                int free = blockMap.freeFileBlocks();
                if (blocks > free) {
                    return "No hay bloques libres suficientes: el archivo necesita " + blocks + " y quedan " + free;
                }
            }
            return null;
        } finally {
            lock.release();
        }
    }

    // Leer: todos ven la estructura, pero en modo usuario un archivo solo se lee si es propio o público.
    public String checkRead(String path, UserMode mode, String user) {
        lock.acquireUninterruptibly();
        try {
            FsNode node = findNode(path);
            if (node == null) {
                return "No existe '" + path + "'";
            }
            if (mode == UserMode.USER) {
                String identity = missingIdentity(user);
                if (identity != null) {
                    return identity;
                }
                if (!node.isDirectory() && !user.equals(node.getOwner()) && node.getPermission() == FilePermission.PRIVATE) {
                    return "'" + path + "' es privado y pertenece a '" + node.getOwner() + "'";
                }
            }
            return null;
        } finally {
            lock.release();
        }
    }

    // Actualizar = cambiar el nombre. Solo el administrador.
    public String checkUpdate(String path, String newName, UserMode mode, String user) {
        lock.acquireUninterruptibly();
        try {
            FsNode node = findNode(path);
            if (node == null) {
                return "No existe '" + path + "'";
            }
            if (node == root) {
                return "No se puede renombrar la raíz";
            }
            if (mode == UserMode.USER) {
                return "Solo el administrador puede cambiar nombres";
            }
            if (!validName(newName)) {
                return "El nombre nuevo no es válido: no puede estar vacío, tener / ni pasar de " + MAX_NAME_LENGTH + " caracteres";
            }
            FsNode clash = node.getParent().findChild(newName);
            if (clash != null && clash != node) {
                return "Ya existe un elemento llamado '" + newName + "' en ese directorio";
            }
            return null;
        } finally {
            lock.release();
        }
    }

    // Eliminar. En modo usuario solo archivos propios; los directorios solo el administrador.
    public String checkDelete(String path, UserMode mode, String user) {
        lock.acquireUninterruptibly();
        try {
            FsNode node = findNode(path);
            if (node == null) {
                return "No existe '" + path + "'";
            }
            if (node == root) {
                return "No se puede eliminar la raíz";
            }
            if (mode == UserMode.USER) {
                String identity = missingIdentity(user);
                if (identity != null) {
                    return identity;
                }
                if (node.isDirectory()) {
                    return "Solo el administrador puede eliminar directorios";
                }
                if (!user.equals(node.getOwner())) {
                    return "Solo se pueden eliminar archivos propios: '" + path + "' pertenece a '" + node.getOwner() + "'";
                }
            }
            return null;
        } finally {
            lock.release();
        }
    }

    // ================================================================ aplicar cambios

    public void createDirectory(String path, String owner, FilePermission permission, String creator) {
        lock.acquireUninterruptibly();
        try {
            FsNode parent = prepareParent(path, owner, permission);
            parent.addChild(new FsNode(baseName(path), FileType.DIRECTORY, owner, permission, -1, 0, creator));
        } finally {
            lock.release();
        }
    }

    // Crea un archivo y le reserva 'blocks' bloques libres encadenados.
    public void createFile(String path, int blocks, String owner, FilePermission permission, String creator) {
        if (blocks < 1) {
            throw new IllegalArgumentException("Un archivo debe tener al menos 1 bloque");
        }
        lock.acquireUninterruptibly();
        try {
            FsNode parent = prepareParent(path, owner, permission);
            int start = blockMap.allocateFile(path, blocks);
            if (start == -1) {
                throw new IllegalArgumentException("No hay bloques libres suficientes: '" + path + "' necesita "
                        + blocks + " y quedan " + blockMap.freeFileBlocks());
            }
            parent.addChild(new FsNode(baseName(path), FileType.FILE, owner, permission, start, blocks, creator));
        } finally {
            lock.release();
        }
    }

    // Registra un archivo que ya existe en el disco, con sus bloques consecutivos desde 'startBlock'.
    // Lo usa el cargador del JSON.
    public void loadFile(String path, int startBlock, int blocks, String owner, FilePermission permission, String creator) {
        lock.acquireUninterruptibly();
        try {
            FsNode parent = prepareParent(path, owner, permission);
            // si los bloques no caben o están ocupados, assign lanza la excepción antes de crear el nodo
            blockMap.assign(BlockOwnerType.FILE, path, startBlock, blocks);
            parent.addChild(new FsNode(baseName(path), FileType.FILE, owner, permission, startBlock, blocks, creator));
        } finally {
            lock.release();
        }
    }

    // Elimina un archivo (libera sus bloques) o un directorio con todo lo que contiene.
    // Devuelve cuántos bloques se liberaron.
    public int delete(String path) {
        lock.acquireUninterruptibly();
        try {
            FsNode node = findNode(path);
            if (node == null) {
                throw new IllegalArgumentException("No existe '" + path + "'");
            }
            if (node == root) {
                throw new IllegalArgumentException("No se puede eliminar la raíz");
            }
            int freed = deleteSubtree(node);
            node.getParent().removeChild(node);
            return freed;
        } finally {
            lock.release();
        }
    }

    // Cambia el nombre de un archivo o directorio. Los bloques de lo que cambia de ruta se
    // vuelven a rotular en el mapa del disco.
    public void rename(String path, String newName) {
        lock.acquireUninterruptibly();
        try {
            FsNode node = findNode(path);
            if (node == null) {
                throw new IllegalArgumentException("No existe '" + path + "'");
            }
            if (node == root) {
                throw new IllegalArgumentException("No se puede renombrar la raíz");
            }
            if (!validName(newName)) {
                throw new IllegalArgumentException("El nombre nuevo no es válido: '" + newName + "'");
            }
            FsNode clash = node.getParent().findChild(newName);
            if (clash != null && clash != node) {
                throw new IllegalArgumentException("Ya existe un elemento llamado '" + newName + "' en ese directorio");
            }
            node.setName(newName);
            relabelSubtree(node);
        } finally {
            lock.release();
        }
    }

    // ================================================================ consultas

    public boolean exists(String path) {
        lock.acquireUninterruptibly();
        try {
            return findNode(path) != null;
        } finally {
            lock.release();
        }
    }

    // Datos de una entrada, o null si no existe.
    public EntryInfo info(String path) {
        lock.acquireUninterruptibly();
        try {
            FsNode node = findNode(path);
            return node == null ? null : toInfo(node, split(path).length);
        } finally {
            lock.release();
        }
    }

    // Primer bloque libre de la zona de archivos (donde empezaría un archivo nuevo), o -1 si no hay.
    public int firstFreeBlock() {
        lock.acquireUninterruptibly();
        try {
            com.avilaos.disk.BlockInfo[] map = blockMap.snapshot();
            for (int i = 0; i < blockMap.getFileAreaBlocks(); i++) {
                if (map[i].type() == BlockOwnerType.FREE) {
                    return i;
                }
            }
            return -1;
        } finally {
            lock.release();
        }
    }

    public int freeBlocks() {
        return blockMap.freeFileBlocks();
    }

    // Todas las entradas en preorden (cada directorio antes de lo que contiene), con su profundidad:
    // la interfaz arma con esto el JTree y la tabla de asignación. La raíz es la primera (ruta "/").
    public EntryInfo[] snapshot() {
        lock.acquireUninterruptibly();
        try {
            EntryInfo[] entries = new EntryInfo[countNodes(root)];
            fill(root, 0, entries, 0);
            return entries;
        } finally {
            lock.release();
        }
    }

    // ================================================================ internos (sin tomar el semáforo)

    // Valida la ruta y devuelve el directorio padre donde se agregará el nodo nuevo.
    private FsNode prepareParent(String path, String owner, FilePermission permission) {
        String[] parts = split(path);
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("La ruta '" + path + "' no es válida: debe empezar con / y no tener nombres vacíos");
        }
        if (owner == null || owner.trim().isEmpty()) {
            throw new IllegalArgumentException("'" + path + "' necesita un dueño");
        }
        if (permission == null) {
            throw new IllegalArgumentException("'" + path + "' necesita un permiso (público o privado)");
        }
        String parentPath = parentPath(path);
        FsNode parent = findNode(parentPath);
        if (parent == null) {
            throw new IllegalArgumentException("No existe el directorio '" + parentPath + "'");
        }
        if (!parent.isDirectory()) {
            throw new IllegalArgumentException("'" + parentPath + "' es un archivo, no un directorio");
        }
        if (parent.findChild(baseName(path)) != null) {
            throw new IllegalArgumentException("Ya existe '" + path + "'");
        }
        return parent;
    }

    private int deleteSubtree(FsNode node) {
        int freed = 0;
        if (node.isDirectory()) {
            while (node.childCount() > 0) {
                FsNode child = node.childAt(0);
                freed += deleteSubtree(child);
                node.removeChild(child);
            }
        } else if (node.getStartBlock() >= 0) {
            freed += blockMap.free(node.getStartBlock());
        }
        return freed;
    }

    private void relabelSubtree(FsNode node) {
        if (node.isDirectory()) {
            for (int i = 0; i < node.childCount(); i++) {
                relabelSubtree(node.childAt(i));
            }
        } else {
            blockMap.relabel(node.getStartBlock(), node.getPath());
        }
    }

    private FsNode findNode(String path) {
        String[] parts = split(path);
        if (parts == null) {
            return null;
        }
        FsNode current = root;
        for (int i = 0; i < parts.length; i++) {
            if (!current.isDirectory()) {
                return null;
            }
            current = current.findChild(parts[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    // "/a/b/c" -> {"a","b","c"}; "/" -> {} ; null si la ruta no es válida
    private static String[] split(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        if (path.equals("/")) {
            return new String[0];
        }
        String[] parts = path.substring(1).split("/", -1);   // -1 conserva los vacíos: "/a/" sería inválido
        for (int i = 0; i < parts.length; i++) {
            if (!validName(parts[i])) {
                return null;
            }
        }
        return parts;
    }

    private static boolean validName(String name) {
        return name != null && !name.trim().isEmpty() && name.length() <= MAX_NAME_LENGTH
                && name.indexOf('/') < 0 && !name.equals(".") && !name.equals("..");
    }

    private static String parentPath(String path) {
        int last = path.lastIndexOf('/');
        return last <= 0 ? "/" : path.substring(0, last);
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String missingIdentity(String user) {
        return user == null || user.trim().isEmpty() ? "En modo usuario hay que indicar con qué usuario se trabaja" : null;
    }

    private static int countNodes(FsNode node) {
        int count = 1;
        if (node.isDirectory()) {
            for (int i = 0; i < node.childCount(); i++) {
                count += countNodes(node.childAt(i));
            }
        }
        return count;
    }

    // Llena el arreglo en preorden y devuelve la siguiente posición libre
    private int fill(FsNode node, int depth, EntryInfo[] entries, int index) {
        entries[index++] = toInfo(node, depth);
        if (node.isDirectory()) {
            for (int i = 0; i < node.childCount(); i++) {
                index = fill(node.childAt(i), depth + 1, entries, index);
            }
        }
        return index;
    }

    private static EntryInfo toInfo(FsNode node, int depth) {
        return new EntryInfo(node.getPath(), node.getName(), node.getType(), node.getOwner(), node.getPermission(),
                node.getStartBlock(), node.getBlocks(), node.getCreator(), depth);
    }
}
