package com.avilaos.enums;

// Permiso de un archivo: privado (solo su dueño) o público (lo puede leer cualquiera).
public enum FilePermission {
    PUBLIC,
    PRIVATE;

    // El JSON trae "public" o "private" en minúsculas. Devuelve null si el texto no corresponde,
    // para que el validador muestre un mensaje claro en vez de lanzar una excepción.
    public static FilePermission fromText(String text) {
        if (text == null) {
            return null;
        }
        try {
            return valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
