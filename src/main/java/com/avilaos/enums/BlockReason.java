/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Enum.java to edit this template
 */
package com.avilaos.enums;

/**
 *
 * @author aleja
 */
public enum BlockReason {
    NONE,
    SEMAPHORE_MUTEX,
    BUFFER_FULL,
    BUFFER_EMPTY,
    NETWORK_LATENCY,
    IO_WAIT,        // E/S por DMA: espera una cantidad fija de ciclos
    DISK_IO         // espera en la cola del disco: sin temporizador, lo despierta el disco al terminar
}
