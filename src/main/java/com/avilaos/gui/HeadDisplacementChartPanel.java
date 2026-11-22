package com.avilaos.gui;

import com.avilaos.enums.DiskPolicy;
import com.avilaos.metrics.DiskMetricsCollector;
import java.awt.BorderLayout;
import javax.swing.JPanel;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

// Gráfica del desplazamiento ACUMULADO del cabezal contra el ciclo (punto de métricas del enunciado), para
// comparar las políticas de disco. Hay una serie por política: cada punto va a la serie de la política que
// estaba activa en ese ciclo, así al cambiar de política en ejecución el trazo cambia de color y se ve
// cuál sube más rápido. Lee el historial de DiskMetricsCollector y agrega solo los puntos nuevos.
public class HeadDisplacementChartPanel extends JPanel {

    private final DiskMetricsCollector collector;
    private final XYSeries[] series;
    private int lastCycleDrawn = 0;

    public HeadDisplacementChartPanel(DiskMetricsCollector collector) {
        this.collector = collector;
        DiskPolicy[] policies = DiskPolicy.values();
        series = new XYSeries[policies.length];
        XYSeriesCollection dataset = new XYSeriesCollection();
        for (int i = 0; i < policies.length; i++) {
            series[i] = new XYSeries(policies[i].toString());
            series[i].setMaximumItemCount(DiskMetricsCollector.HISTORY_SIZE);
            dataset.addSeries(series[i]);
        }
        JFreeChart chart = ChartFactory.createXYLineChart("Desplazamiento acumulado del cabezal",
                "Ciclo", "Desplazamiento acumulado (posiciones)", dataset, PlotOrientation.VERTICAL, true, true, false);
        setLayout(new BorderLayout());
        add(new ChartPanel(chart), BorderLayout.CENTER);
    }

    public void refresh() {
        DiskMetricsCollector.DisplacementSnapshot snap = collector.getSnapshot();
        if (snap.latestCycle() <= lastCycleDrawn) {
            return;
        }
        for (int k = 0; k < snap.displacement().length; k++) {
            int cycle = snap.oldestCycle() + k;
            if (cycle > lastCycleDrawn) {
                series[snap.policy()[k]].add(cycle, snap.displacement()[k], false);   // sin notificar en cada punto
            }
        }
        for (int i = 0; i < series.length; i++) {
            series[i].fireSeriesChanged();
        }
        lastCycleDrawn = snap.latestCycle();
    }
}
