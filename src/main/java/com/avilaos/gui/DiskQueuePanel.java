package com.avilaos.gui;

import com.avilaos.disk.DiskRequest;
import com.avilaos.disk.DiskStatus;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.UserMode;
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
import javax.swing.table.DefaultTableModel;

// Cola de E/S del disco con la operación de cada solicitud, la solicitud en curso, la política en uso
// y el selector para cambiarla mientras el sistema corre. Solo el administrador puede cambiar la política
// (en modo usuario el selector queda deshabilitado y se explica por qué).
public class DiskQueuePanel extends JPanel {

    private static final String[] COLUMNS = {"Proceso", "Operación", "Bloque", "Bloques a transferir", "Llegó en el ciclo"};

    private final Disk disk;
    private final UserSession session;
    private final DefaultTableModel model = new DefaultTableModel(new Object[0][0], COLUMNS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JLabel inServiceLabel = new JLabel(" ");
    private final JLabel policyLabel = new JLabel(" ");
    private final JLabel hintLabel = new JLabel(" ");
    private final JComboBox<DiskPolicy> policyBox = new JComboBox<>(DiskPolicy.values());
    private final JButton applyButton = new JButton("Aplicar");

    public DiskQueuePanel(Disk disk, UserSession session) {
        this.disk = disk;
        this.session = session;
        policyBox.setSelectedItem(disk.snapshot().policy());
        applyButton.addActionListener(e -> applyPolicy());

        JPanel policyRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        policyRow.add(new JLabel("Política en uso:"));
        policyRow.add(policyLabel);
        policyRow.add(new JLabel("   Cambiar a:"));
        policyRow.add(policyBox);
        policyRow.add(applyButton);
        policyRow.add(hintLabel);

        JPanel top = new JPanel(new BorderLayout());
        top.add(policyRow, BorderLayout.NORTH);
        top.add(inServiceLabel, BorderLayout.SOUTH);
        inServiceLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
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
        DiskStatus s = disk.snapshot();
        policyLabel.setText(s.policy().toString());
        inServiceLabel.setText(s.current() == null ? "Disco libre" : "Atendiendo: " + s.current());
        boolean admin = session.getMode() == UserMode.ADMINISTRATOR;
        policyBox.setEnabled(admin);
        applyButton.setEnabled(admin);
        hintLabel.setText(admin ? " " : "  Solo el administrador puede cambiar la política de disco");

        model.setRowCount(0);
        DiskRequest[] queue = s.queue();
        for (int i = 0; i < queue.length; i++) {
            DiskRequest r = queue[i];
            model.addRow(new Object[]{"P" + r.getProcessId(), r.getOperation(), r.getBlock(),
                r.getBlockCount(), r.getArrivalCycle()});
        }
    }
}
