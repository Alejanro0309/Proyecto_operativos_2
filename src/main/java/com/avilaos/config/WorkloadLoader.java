package com.avilaos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

// Lee el JSON de procesos y configuración. Cualquier problema con el archivo sale como
// IllegalArgumentException("Archivo inválido: ..."). load no valida los valores: eso lo hace WorkloadValidator.
public final class WorkloadLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private WorkloadLoader() {
    }

    public static WorkloadConfig load(File file) {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            WorkloadConfig config = GSON.fromJson(reader, WorkloadConfig.class);
            if (config == null) {
                throw new IllegalArgumentException("Archivo inválido: está vacío");
            }
            if (config.processes != null) {
                for (int i = 0; i < config.processes.length; i++) {
                    if (config.processes[i] == null) {
                        throw new IllegalArgumentException("Archivo inválido: el proceso " + (i + 1)
                                + " de \"processes\" está vacío (null)");
                    }
                }
            }
            return config;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Archivo inválido: no es un JSON de procesos de ÁvilaOS ("
                    + e.getMessage() + ")");
        } catch (IOException e) {
            throw new IllegalArgumentException("Archivo inválido: no se pudo leer (" + e.getMessage() + ")");
        }
    }
}
