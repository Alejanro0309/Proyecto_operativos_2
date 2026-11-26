package com.avilaos.config;

// JSON del sistema de archivos (formato del enunciado del Proyecto 2). Gson llena estos campos directo.
// Cada archivo trae su primer bloque y su cantidad (bloques consecutivos). Cuando el estado se guarda
// después de crear y borrar archivos, cada archivo trae además 'chain': la lista exacta de sus bloques.
// Los directorios no llevan bloques.
public class FileSystemConfig {

    public DiskSettings diskConfig;
    public Entry[] fileSystem;

    public FileSystemConfig() {
    }

    public static class DiskSettings {

        public int totalBlocks;
        public int initialHead;
    }

    public static class Entry {

        public String path;
        public String type;            // FILE o DIRECTORY
        public Integer startBlock;     // solo archivos
        public Integer blocks;         // solo archivos
        public String owner;
        public String permissions;     // public o private
        public int[] chain;            // opcional: bloques del archivo en orden (estado guardado)

        public int start() {
            return startBlock == null ? 0 : startBlock;
        }

        public int size() {
            return blocks == null ? 0 : blocks;
        }
    }
}
