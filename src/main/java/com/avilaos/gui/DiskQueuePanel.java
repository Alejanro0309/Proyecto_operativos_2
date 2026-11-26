package com.avilaos.gui;

import com.avilaos.disk.DiskRequest;
import com.avilaos.disk.DiskStatus;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.hardware.Disk;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;

// Cola de E/S del disco (con la operación de cada solicitud) y selector de política.
// Cambiar la política solo manda la orden a Disk.setPolicy; la interfaz no decide nada.
public class DiskQueuePanel extends JPanel {

    private static final String[] COLUMNS = {"Posición", "Proceso", "Operación", "Bloque", "Bloques a transferir", "Llegó (ciclo)"};

    private final Disk disk;
    private final JLabel activeLabel = new JLabel("—");
    private final JComboBox<DiskPolicy> policyBox = new JComboBox<>(DiskPolicy.values());
    private final TitledBorder border = new TitledBorder("Cola de E/S del disco");
    private final DefaultTableModel model = new DefaultTableModel(new Object[0][0], COLUMNS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    public DiskQueuePanel(Disk disk) {
        if (disk == null) {
            throw new IllegalArgumentException("El panel necesita el disco");
        }
        this.disk = disk;
        policyBox.setSelectedItem(disk.snapshot().policy());
        JButton apply = new JButton("Aplicar");
        apply.addActionListener(e -> applyPolicy());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Política en uso:"));
        top.add(activeLabel);
        top.add(new JLabel("   Cambiar a:"));
        top.add(policyBox);
        top.add(apply);

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(border);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    private void applyPolicy() {
        try {
            disk.setPolicy((DiskPolicy) policyBox.getSelectedItem());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Dato inválido", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void refresh() {
        DiskStatus st = disk.snapshot();
        activeLabel.setText(st.policy().name());
        model.setRowCount(0);
        int count = 0;
        if (st.current() != null) {
            model.addRow(row("En atención", st.current()));
            count++;
        }
        for (int i = 0; i < st.queue().length; i++) {
            model.addRow(row(String.valueOf(i + 1), st.queue()[i]));
            count++;
        }
        border.setTitle("Cola de E/S del disco (" + count + ")");
        repaint();
    }

    private static Object[] row(String position, DiskRequest r) {
        return new Object[]{position, "P" + r.getProcessId(), r.getOperation(), r.getBlock(),
            r.getBlockCount(), r.getArrivalCycle()};
    }
}
