package com.avilaos.config;

// JSON de procesos y configuración (formato del enunciado del Proyecto 2). Gson llena estos campos directo.
// Campos opcionales (no vienen en el JSON del enunciado): globalConfig.headSpeed (por omisión 10),
// y por proceso priority (1), owner (admin), newName (UPDATE: el nombre con _v2 antes de la extensión),
// y para CREATE: createType (FILE), fileSizeBlocks y permissions (public).
public class WorkloadConfig {

    public GlobalSettings globalConfig;
    public ComputerSettings computer;
    public ProcessEntry[] processes;

    public WorkloadConfig() {
    }

    public static class GlobalSettings {

        public int cycleDurationMs;
        public int swapSizeBlocks;
        public String initialDiskPolicy;
        public int headSpeed;                 // 0 = valor por omisión
    }

    public static class ComputerSettings {

        public int ramCapacity;
        public String policy;
        public int quantum;
    }

    public static class ProcessEntry {

        public String name;
        public int memory;
        public int deadline;
        public int instructions;
        public String targetPath;
        public String operation;
        public Integer priority;
        public String owner;
        public String newName;
        public String createType;
        public int fileSizeBlocks;
        public String permissions;
    }
}
