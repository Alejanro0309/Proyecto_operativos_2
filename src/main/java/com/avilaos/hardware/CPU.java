/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.avilaos.hardware;

import com.avilaos.enums.CPUMode;
import com.avilaos.enums.ProcessState;
import com.avilaos.model.ProcessControlBlock;
import java.util.function.BiConsumer;

public class CPU implements ClockListener {

    private final int computerId;
    private ProcessControlBlock running;
    private CPUMode mode;
    private int quantumUsed;
    private int busyCycles;
    private int idleCycles;
    private boolean busyLastCycle;
    private volatile ProcessControlBlock lastExecuted;

    private int currentCycle;
    private BiConsumer<Integer, CPUMode> onModeChange;

    public CPU(int computerId) {
        this.computerId = computerId;
        this.running = null;
        this.mode = CPUMode.USER_MODE;
        this.quantumUsed = 0;
    }

    public void setOnModeChange(BiConsumer<Integer, CPUMode> onModeChange) {
        this.onModeChange = onModeChange;
    }

    private void changeMode(CPUMode newMode) {
        if (this.mode != newMode) {
            this.mode = newMode;
            if (onModeChange != null) {
                onModeChange.accept(currentCycle, newMode);
            }
        }
    }

    /** Carga un proceso READY en la CPU (despacho). */
    public void dispatch(ProcessControlBlock process, int cycle) {
        if (running != null) {
            throw new IllegalStateException("CPU " + computerId + " ocupada por P" + running.getId());
        }
        if (process.getState() != ProcessState.READY) {
            throw new IllegalStateException("Solo se despacha un proceso READY: " + process);
        }
        this.currentCycle = cycle;
        process.setState(ProcessState.RUNNING);
        process.markFirstRun(cycle);
        running = process;
        quantumUsed = 0;
    }

    /**
     * Saca al proceso actual de la CPU y lo devuelve (el kernel decide a qué
     * cola va: READY si fue expropiado, BLOCKED o TERMINATED).
     */
    public ProcessControlBlock release() {
        ProcessControlBlock previous = running;
        running = null;
        changeMode(CPUMode.USER_MODE);
        quantumUsed = 0;
        return previous;
    }

    /** Tick del reloj: ejecuta un ciclo de CPU (instrucción de usuario u ocio). */
    @Override
    public void onTick(int cycle) {
        this.currentCycle = cycle;
        executeCycle();
    }

    /**
     * Ejecuta un ciclo: si hay proceso, ejecuta 1 instrucción de usuario;
     * si no, el ciclo se cuenta como ocioso en modo usuario por defecto.
     */
    public void executeCycle() {
        if (running == null) {
            changeMode(CPUMode.USER_MODE);
            idleCycles++;
            busyLastCycle = false;
            lastExecuted = null;
            return;
        }
        changeMode(CPUMode.USER_MODE);
        running.executeInstruction();
        busyCycles++;
        quantumUsed++;
        busyLastCycle = true;
        lastExecuted = running;
    }

    /**
     * Ejecuta un ciclo en modo SO: una llamada al sistema del proceso actual.
     */
    public void executeKernelInstruction() {
        if (running == null) {
            throw new IllegalStateException("No hay proceso para ejecutar una llamada al sistema");
        }
        changeMode(CPUMode.KERNEL_MODE);
        running.executeInstruction();
        busyCycles++;
        quantumUsed++;
        busyLastCycle = true;
        lastExecuted = running;
    }

    /**
     * Ciclo de trabajo del SO que NO es una instrucción del proceso.
     */
    public void executeKernelOverhead() {
        changeMode(CPUMode.KERNEL_MODE);
        busyCycles++;
        busyLastCycle = true;
        lastExecuted = running;
    }

    // ---------------------------------------------------------------- consultas

    public boolean isIdle() {
        return running == null;
    }

    public ProcessControlBlock getRunning() {
        return running;
    }
    
    public ProcessControlBlock getLastExecuted() {
        return lastExecuted;
    }

    public CPUMode getMode() {
        return mode;
    }

    public int getQuantumUsed() {
        return quantumUsed;
    }

    public int getBusyCycles() {
        return busyCycles;
    }

    public int getIdleCycles() {
        return idleCycles;
    }

    public boolean wasBusyLastCycle() {
        return busyLastCycle;
    }

    public double getUtilizationPercent() {
        int total = busyCycles + idleCycles;
        return total == 0 ? 0.0 : (busyCycles * 100.0) / total;
    }

    public int getComputerId() {
        return computerId;
    }
}