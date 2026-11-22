package com.avilaos.gui;

import com.avilaos.enums.DiskPolicy;
import com.avilaos.kernel.Kernel;
import com.avilaos.metrics.DiskMetrics;
import com.avilaos.metrics.DiskMetricsCollector;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

// Métricas del disco (desplazamiento total, espera promedio, atendidas por política) y de la
// suspensión (suspensiones y reactivaciones). No calcula nada: solo da formato.
public class DiskMetricsPanel extends JPanel {

    private final DiskMetricsCollector collector;
    private final Kernel kernel;
    private final DefaultTableModel model = new DefaultTableModel(new Object[0][0], new String[]{"Métrica", "Valor"}) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    public DiskMetricsPanel(DiskMetricsCollector collector, Kernel kernel) {
        this.collector = collector;
        this.kernel = kernel;
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("Métricas del disco y de la suspensión"));
        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        DiskMetrics m = collector.getMetrics();
        model.setRowCount(0);
        add("Desplazamiento total del cabezal (bloques)", String.valueOf(m.totalDisplacement()));
        add("Solicitudes atendidas", String.valueOf(m.served()));
        add("Tiempo promedio de espera en la cola de E/S (ciclos)", fmt(m.averageWait(), 2));
        add("Utilización del disco (%)", fmt(m.utilization(), 1));
        DiskPolicy[] policies = DiskPolicy.values();
        for (int i = 0; i < policies.length; i++) {
            add("Solicitudes atendidas con " + policies[i], String.valueOf(m.servedPerPolicy()[i]));
        }
        add("Cantidad de suspensiones", String.valueOf(kernel.getSuspensionCount()));
        add("Cantidad de reactivaciones", String.valueOf(kernel.getReactivationCount()));
    }

    private void add(String label, String value) {
        model.addRow(new Object[]{label, value});
    }

    private static String fmt(double value, int decimals) {
        return Double.isNaN(value) ? "—" : String.format("%." + decimals + "f", value);
    }
}
