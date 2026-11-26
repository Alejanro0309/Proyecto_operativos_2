package com.avilaos.disk;

import com.avilaos.enums.BlockOwnerType;

// Copia inmutable de un bloque, para que la interfaz pinte el mapa sin tocar el real.
public record BlockInfo(BlockOwnerType type, String owner, int next) {
}
