package com.avilaos.config;

import com.avilaos.disk.BlockMap;
import com.avilaos.enums.BlockOwnerType;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.filesystem.FileSystem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

// Lee el JSON del sistema de archivos, arma el BlockMap y el FileSystem con su contenido, y guarda el
// estado actual para poder cargarlo en una ejecución futura. Cualquier problema sale como
// IllegalArgumentException("Archivo inválido: ...") o con el motivo del validador.
public final class FileSystemLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String LOADED_BY = "carga";     // "proceso que lo creó" de lo que viene del JSON

    // Todo lo que sale de cargar el archivo: el sistema de archivos, su mapa de bloques y los datos del disco
    public record LoadedFileSystem(FileSystem fileSystem, BlockMap blockMap, int totalBlocks, int initialHead) {
    }

    private FileSystemLoader() {
    }

    // Solo lee y comprueba que sea un JSON con el formato; los valores los revisa FileSystemValidator
    public static FileSystemConfig load(File file) {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            FileSystemConfig config = GSON.fromJson(reader, FileSystemConfig.class);
            if (config == null) {
                throw new IllegalArgumentException("Archivo inválido: está vacío");
            }
            if (config.fileSystem != null) {
                for (int i = 0; i < config.fileSystem.length; i++) {
                    if (config.fileSystem[i] == null) {
                        throw new IllegalArgumentException("Archivo inválido: la entrada " + (i + 1)
                                + " de \"fileSystem\" está vacía (null)");
                    }
                }
            }
            return config;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Archivo inválido: no es un JSON del sistema de archivos de ÁvilaOS ("
                    + e.getMessage() + ")");
        } catch (IOException e) {
            throw new IllegalArgumentException("Archivo inválido: no se pudo leer (" + e.getMessage() + ")");
        }
    }

    // Construye el disco: primero los directorios (del más cercano a la raíz al más profundo, para que cada
    // padre exista) y luego los archivos. swapBlocks sale del JSON de procesos, por eso se pide aparte.
    public static LoadedFileSystem build(FileSystemConfig cfg, int swapBlocks) {
        FileSystemValidator.validate(cfg);
        if (swapBlocks < 0) {
            throw new IllegalArgumentException("El tamaño del swap no puede ser negativo");
        }
        int total = cfg.diskConfig.totalBlocks;
        BlockMap map = new BlockMap(total, swapBlocks);
        FileSystem fs = new FileSystem(map);
        FileSystemConfig.Entry[] entries = cfg.fileSystem == null ? new FileSystemConfig.Entry[0] : cfg.fileSystem;

        int maxDepth = 0;
        for (int i = 0; i < entries.length; i++) {
            maxDepth = Math.max(maxDepth, depthOf(entries[i].path));
        }
        for (int depth = 1; depth <= maxDepth; depth++) {
            for (int i = 0; i < entries.length; i++) {
                FileSystemConfig.Entry e = entries[i];
                if (FileSystemValidator.parseType(e.type) == FileType.DIRECTORY && depthOf(e.path) == depth) {
                    fs.createDirectory(e.path, e.owner.trim(), FilePermission.fromText(e.permissions), LOADED_BY);
                }
            }
        }
        for (int i = 0; i < entries.length; i++) {
            FileSystemConfig.Entry e = entries[i];
            if (FileSystemValidator.parseType(e.type) != FileType.FILE) {
                continue;
            }
            FilePermission permission = FilePermission.fromText(e.permissions);
            if (e.chain != null && e.chain.length > 0) {
                fs.loadFileChain(e.path, e.chain, e.owner.trim(), permission, LOADED_BY);
            } else {
                fs.loadFile(e.path, e.start(), e.size(), e.owner.trim(), permission, LOADED_BY);
            }
        }
        return new LoadedFileSystem(fs, map, total, cfg.diskConfig.initialHead);
    }
   
    

    // Guarda el estado actual: directorios, archivos, bloques (la lista exacta de cada archivo), dueños y
    // permisos, más el tamaño del disco y la posición del cabezal. Se puede volver a cargar con load + build.
    public static void save(FileSystem fs, int totalBlocks, int head, File file) {
        if (head < 0 || head >= totalBlocks) {
            throw new IllegalArgumentException("La posición del cabezal debe estar entre 0 y " + (totalBlocks - 1));
        }
        FileSystem.EntryInfo[] snapshot = fs.snapshot();       // la primera entrada es la raíz: no se guarda
        FileSystemConfig cfg = new FileSystemConfig();
        cfg.diskConfig = new FileSystemConfig.DiskSettings();
        cfg.diskConfig.totalBlocks = totalBlocks;
        cfg.diskConfig.initialHead = head;
        cfg.fileSystem = new FileSystemConfig.Entry[snapshot.length - 1];
        for (int i = 1; i < snapshot.length; i++) {
            FileSystem.EntryInfo info = snapshot[i];
            FileSystemConfig.Entry e = new FileSystemConfig.Entry();
            e.path = info.path();
            e.type = info.type().name();
            e.owner = info.owner();
            e.permissions = info.permission().name().toLowerCase();
            if (info.type() == FileType.FILE) {
                e.startBlock = info.startBlock();
                e.blocks = info.blocks();
                e.chain = fs.getBlockMap().chainOf(info.startBlock());
            }
            cfg.fileSystem[i - 1] = e;
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(cfg, writer);
        } catch (JsonParseException | IOException e) {
            throw new IllegalArgumentException("Archivo inválido: no se pudo guardar (" + e.getMessage() + ")");
        }
    }

    // Cantidad de niveles de una ruta: /a = 1, /a/b = 2
    private static int depthOf(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }
}
