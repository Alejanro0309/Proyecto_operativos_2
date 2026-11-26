/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Enum.java to edit this template
 */
package com.avilaos.enums;

/**
 *
 * @author aleja
 */
public enum ProcessState {
    NEW,
    READY,
    RUNNING,
    BLOCKED,
    READY_SUSPENDED,     // listo, pero su imagen está en el área de swap (fuera de la RAM)
    BLOCKED_SUSPENDED,   // bloqueado y además en el área de swap
    TERMINATED
}