package com.avilaos.gui;

import com.avilaos.enums.FileType;
import com.avilaos.filesystem.FileSystem;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

// Tabla de asignación de archivos (JTable): color, nombre, bloques asignados, primer bloque, dueño y
// proceso que lo creó. Se actualiza con cada operación CRUD: solo se rearma si algo cambió.
public class AllocationTablePanel extends JPanel {

    private static final String[] COLUMNS = {"Color", "Archivo", "Bloques asignados", "Primer bloque",
        "Dueño", "Proceso que lo creó"};

    private final FileSystem fileSystem;
    private final DefaultTableModel model = new DefaultTableModel(new Object[0][0], COLUMNS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Color.class : Object.class;
        }
    };
    private String lastSignature = null;

    public AllocationTablePanel(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Color.class, new ColorRenderer());
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(new JScrollPane(table), BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        FileSystem.EntryInfo[] entries = fileSystem.snapshot();
        String signature = FileSystemTreeModel.signature(entries);
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        model.setRowCount(0);
        for (int i = 0; i < entries.length; i++) {
            FileSystem.EntryInfo e = entries[i];
            if (e.type() == FileType.FILE) {
                model.addRow(new Object[]{FileColors.forOwner(e.path()), e.path(), e.blocks(), e.startBlock(),
                    e.owner(), e.creator()});
            }
        }
    }

    // Pinta la celda del color del archivo (el mismo del mapa de bloques)
    private static class ColorRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            if (value instanceof Color) {
                c.setBackground((Color) value);
            }
            return c;
        }
    }
}
