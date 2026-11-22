package com.avilaos.gui;

import com.avilaos.enums.BlockOwnerType;
import java.awt.Color;

// Un color estable por archivo, para el mapa de bloques y la tabla de asignación (el enunciado pide
// que, si se usan colores, la tabla los incluya). El color sale de la ruta: el mismo archivo siempre
// se ve igual. Con más archivos que colores se repiten: la tabla y el tooltip identifican el archivo.
public final class FileColors {

    public static final Color FREE = new Color(238, 238, 238);
    public static final Color SWAP = new Color(150, 180, 220);

    private static final Color[] PALETTE = {
        new Color(231, 76, 60), new Color(46, 204, 113), new Color(52, 152, 219), new Color(241, 196, 15),
        new Color(155, 89, 182), new Color(230, 126, 34), new Color(26, 188, 156), new Color(233, 30, 99),
        new Color(139, 195, 74), new Color(121, 85, 72), new Color(0, 188, 212), new Color(255, 152, 0)};

    private FileColors() {
    }

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
