package com.avilaos.gui;

import com.avilaos.enums.BlockOwnerType;
import java.awt.Color;

// Un color por archivo, para el mapa de bloques y la tabla de asignación (el enunciado pide que, si se
// usan colores, la tabla los incluya).
//
// El color se saca del PRIMER BLOQUE del archivo, repartido en orden entre todos los archivos: así dos
// archivos no repiten color (hasta agotar la paleta) y renombrar un archivo no le cambia el color,
// porque sus bloques no se mueven. El mapa de bloques y la tabla usan la misma función con la misma
// lista de primeros bloques, por eso coinciden.
public final class FileColors {

    public static final Color FREE = new Color(238, 238, 238);
    public static final Color SWAP = new Color(150, 180, 220);

    private static final Color[] PALETTE = {
        new Color(231, 76, 60), new Color(46, 204, 113), new Color(52, 152, 219), new Color(241, 196, 15),
        new Color(155, 89, 182), new Color(230, 126, 34), new Color(26, 188, 156), new Color(233, 30, 99),
        new Color(139, 195, 74), new Color(121, 85, 72), new Color(0, 188, 212), new Color(255, 152, 0),
        new Color(63, 81, 181), new Color(205, 220, 57), new Color(244, 143, 177), new Color(96, 125, 139),
        new Color(0, 150, 136), new Color(171, 71, 188), new Color(255, 87, 34), new Color(158, 158, 158)};

    private FileColors() {
    }

    // Color de un archivo: su posición (por número de primer bloque) entre los 'count' primeros bloques
    // de 'starts'. Con más archivos que colores se repiten.
    public static Color forFile(int startBlock, int[] starts, int count) {
        int rank = 0;
        for (int i = 0; i < count; i++) {
            if (starts[i] < startBlock) {
                rank++;
            }
        }
        return PALETTE[rank % PALETTE.length];
    }

    // Respaldo por nombre (solo si un bloque no pertenece a ninguna cadena conocida)
    public static Color forOwner(String owner) {
        int hash = owner == null ? 0 : owner.hashCode();
        return PALETTE[(hash & 0x7fffffff) % PALETTE.length];
    }

    public static Color forBlock(BlockOwnerType type, String owner) {
        if (type == BlockOwnerType.FREE) {
            return FREE;
        }
        return type == BlockOwnerType.SWAP ? SWAP : forOwner(owner);
    }
}