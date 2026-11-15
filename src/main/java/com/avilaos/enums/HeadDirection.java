package com.avilaos.enums;

// Dirección en la que se mueve el cabezal. UP = hacia los bloques mayores.
// La interfaz muestra este valor junto a la posición del cabezal.
public enum HeadDirection {
    UP,
    DOWN;

    public HeadDirection opposite() {
        return this == UP ? DOWN : UP;
    }
}
