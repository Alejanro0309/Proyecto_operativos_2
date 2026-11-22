package com.avilaos.gui;

import com.avilaos.enums.DiskPolicy;
import com.avilaos.hardware.Disk;
import com.avilaos.kernel.Kernel;
import com.avilaos.metrics.DiskMetrics;
import com.avilaos.metrics.DiskMetricsCollector;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

// Métricas del disco y de la suspensión (las que pide el enunciado del Proyecto 2): desplazamiento total
// del cabezal, espera promedio en la cola de E/S, solicitudes atendidas por cada política, y cantidad de
// suspensiones y reactivaciones. No calcula nada: da formato. NaN significa "no aplica todavía" y se muestra "—".
public class DiskMetricsPanel extends JPanel {

    private final DiskMetricsCollector collector;
    private final Disk disk;
    private final Kernel kernel;
    private final DefaultTableModel metricsModel = readOnlyModel(new String[]{"Métrica", "Valor"});
    private final DefaultTableModel perPolicyModel = readOnlyModel(new String[]{"Política de disco", "Solicitudes atendidas"});

    public DiskMetricsPanel(DiskMetricsCollector collector, Disk disk, Kernel kernel) {
        this.collector = collector;
        this.disk = disk;
        this.kernel = kernel;

        JTable metricsTable = new JTable(metricsModel);
        metricsTable.setFillsViewportHeight(true);
        JScrollPane metricsScroll = new JScrollPane(metricsTable);
        metricsScroll.setBorder(BorderFactory.createTitledBorder("Disco y suspensión de procesos"));

        JTable perPolicyTable = new JTable(perPolicyModel);
        perPolicyTable.setFillsViewportHeight(true);
        JScrollPane perPolicyScroll = new JScrollPane(perPolicyTable);
        perPolicyScroll.setBorder(BorderFactory.createTitledBorder("Solicitudes atendidas por cada política"));

        setLayout(new GridLayout(2, 1, 6, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        add(metricsScroll);
        add(perPolicyScroll);
    }

    public void refresh() {
        DiskMetrics m = collector.getMetrics();
        metricsModel.setRowCount(0);
        metricsModel.addRow(new Object[]{"Política de disco en uso", disk.snapshot().policy()});
        metricsModel.addRow(new Object[]{"Desplazamiento total del cabezal (posiciones)", m.totalDisplacement()});
        metricsModel.addRow(new Object[]{"Solicitudes atendidas", m.served()});
        metricsModel.addRow(new Object[]{"Tiempo promedio de espera en la cola de E/S (ciclos)", fmt(m.averageWait(), 2)});
        metricsModel.addRow(new Object[]{"Ciclos con el disco ocupado", m.busyCycles()});
        metricsModel.addRow(new Object[]{"Ciclos con el disco ocioso", m.idleCycles()});
        metricsModel.addRow(new Object[]{"Utilización del disco (%)", fmt(m.utilization(), 1)});
        metricsModel.addRow(new Object[]{"Suspensiones de procesos", kernel.getSuspensionCount()});
        metricsModel.addRow(new Object[]{"Reactivaciones de procesos", kernel.getReactivationCount()});

        perPolicyModel.setRowCount(0);
        DiskPolicy[] policies = DiskPolicy.values();
        for (int i = 0; i < policies.length; i++) {
            perPolicyModel.addRow(new Object[]{policies[i], m.servedPerPolicy()[i]});
        }
    }

    private static DefaultTableModel readOnlyModel(String[] columns) {
        return new DefaultTableModel(new Object[0][0], columns) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static String fmt(double value, int decimals) {
        return Double.isNaN(value) ? "—" : String.format("%." + decimals + "f", value);
    }
}
