package com.avilaos.hardware;

// GlobalClock solo admite UN observador de fin de ciclo. Este agrupa varios (las métricas del
// computador y las del disco) y les avisa en el orden en que se pasaron.
public final class CompositeClockListener implements ClockListener {

    private final ClockListener[] listeners;

    public CompositeClockListener(ClockListener... listeners) {
        if (listeners == null) {
            throw new IllegalArgumentException("Hace falta al menos un observador");
        }
        this.listeners = listeners;
    }

    @Override
    public void onTick(int cycle) {
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onTick(cycle);
        }
    }
}
