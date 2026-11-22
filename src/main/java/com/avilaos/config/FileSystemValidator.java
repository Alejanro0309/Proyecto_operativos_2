package com.avilaos.config;

import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;

// Revisa que el JSON del sistema de archivos sea válido. Si algo falla lanza IllegalArgumentException
// con un mensaje que dice qué entrada está mal y cuál es el rango.
public final class FileSystemValidator {

    // El enunciado pide un disco "limitado: una cantidad razonable, por ejemplo entre 100 y 500 bloques"
    public static final int MIN_BLOCKS = 100;
    public static final int MAX_BLOCKS = 500;
    private static final int MAX_NAME_LENGTH = 50;

    private FileSystemValidator() {
    }

    public static void validate(FileSystemConfig c) {
        if (c == null) {
            throw new IllegalArgumentException("El archivo del sistema de archivos está vacío");
        }
        if (c.diskConfig == null) {
            throw new IllegalArgumentException("Falta diskConfig (totalBlocks e initialHead)");
        }
        int total = c.diskConfig.totalBlocks;
        range("Total de bloques del disco (totalBlocks)", total, MIN_BLOCKS, MAX_BLOCKS);
        range("Posición inicial del cabezal (initialHead)", c.diskConfig.initialHead, 0, total - 1);

        FileSystemConfig.Entry[] entries = c.fileSystem == null ? new FileSystemConfig.Entry[0] : c.fileSystem;
        String[] blockOwner = new String[total];        // quién ocupa cada bloque: detecta bloques repetidos
        for (int i = 0; i < entries.length; i++) {
            validateEntry(entries[i], i, entries, total, blockOwner);
        }
    }

    private static void validateEntry(FileSystemConfig.Entry e, int i, FileSystemConfig.Entry[] all,
            int total, String[] blockOwner) {
        String label = "Entrada " + (i + 1);
        if (e == null) {
            throw new IllegalArgumentException(label + ": datos vacíos");
        }
        if (!validPath(e.path)) {
            throw new IllegalArgumentException(label + ": la ruta '" + e.path
                    + "' no es válida (debe empezar con /, sin nombres vacíos ni de más de " + MAX_NAME_LENGTH + " caracteres)");
        }
        label = "'" + e.path + "'";
        FileType type = parseType(e.type);
        if (type == null) {
            throw new IllegalArgumentException(label + ": type debe ser FILE o DIRECTORY (recibido: '" + e.type + "')");
        }
        if (e.owner == null || e.owner.trim().isEmpty()) {
            throw new IllegalArgumentException(label + ": el dueño (owner) no puede estar vacío");
        }
        if (FilePermission.fromText(e.permissions) == null) {
            throw new IllegalArgumentException(label + ": permissions debe ser 'public' o 'private' (recibido: '"
                    + e.permissions + "')");
        }
        for (int j = 0; j < i; j++) {
            if (all[j] != null && e.path.equals(all[j].path)) {
                throw new IllegalArgumentException("La ruta '" + e.path + "' está repetida");
            }
        }
        String parent = parentPath(e.path);
        if (!parent.equals("/") && !isDirectoryListed(all, parent)) {
            throw new IllegalArgumentException(label + ": su directorio '" + parent
                    + "' no está en el archivo (o no es un directorio)");
        }
        if (type == FileType.FILE) {
            validateBlocks(e, label, total, blockOwner);
        }
    }

    private static void validateBlocks(FileSystemConfig.Entry e, String label, int total, String[] blockOwner) {
        range(label + " - blocks", e.size(), 1, total);
        if (e.chain != null && e.chain.length > 0) {
            if (e.chain.length != e.size()) {
                throw new IllegalArgumentException(label + ": chain tiene " + e.chain.length
                        + " bloques y blocks dice " + e.size());
            }
            for (int k = 0; k < e.chain.length; k++) {
                range(label + " - bloque de la cadena", e.chain[k], 0, total - 1);
                mark(blockOwner, e.chain[k], e.path);
            }
            return;
        }
        if (e.start() < 0 || e.start() + e.size() > total) {
            throw new IllegalArgumentException(label + ": los bloques " + e.start() + " a "
                    + (e.start() + e.size() - 1) + " no caben en el disco (0 a " + (total - 1) + ")");
        }
        for (int b = e.start(); b < e.start() + e.size(); b++) {
            mark(blockOwner, b, e.path);
        }
    }

    // Anota el dueño del bloque; si ya tenía uno, dos archivos se pisan
    private static void mark(String[] blockOwner, int block, String path) {
        if (blockOwner[block] != null) {
            throw new IllegalArgumentException("Los archivos '" + blockOwner[block] + "' y '" + path
                    + "' usan el mismo bloque (" + block + ")");
        }
        blockOwner[block] = path;
    }

    private static boolean isDirectoryListed(FileSystemConfig.Entry[] all, String path) {
        for (int i = 0; i < all.length; i++) {
            if (all[i] != null && path.equals(all[i].path) && parseType(all[i].type) == FileType.DIRECTORY) {
                return true;
            }
        }
        return false;
    }

    // Devuelve null si el texto no corresponde a FILE ni a DIRECTORY
    public static FileType parseType(String text) {
        if (text == null) {
            return null;
        }
        try {
            return FileType.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Empieza con /, no es solo la raíz y ningún nombre está vacío, es '.' o '..' ni pasa del máximo
    static boolean validPath(String path) {
        if (path == null || !path.startsWith("/") || path.length() < 2) {
            return false;
        }
        String[] parts = path.substring(1).split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i];
            if (name.trim().isEmpty() || name.length() > MAX_NAME_LENGTH || name.equals(".") || name.equals("..")) {
                return false;
            }
        }
        return true;
    }

    static String parentPath(String path) {
        int last = path.lastIndexOf('/');
        return last <= 0 ? "/" : path.substring(0, last);
    }

    private static void range(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " debe estar entre " + min + " y " + max
                    + " (recibido: " + value + ")");
        }
    }
}
