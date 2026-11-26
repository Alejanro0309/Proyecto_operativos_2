package com.avilaos.gui;

import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.UserMode;
import com.avilaos.filesystem.FileOperation;
import com.avilaos.filesystem.FileOperationRequester;
import com.avilaos.filesystem.FileSystem;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

// Reemplaza a ProcessDialog en el Proyecto 2: el usuario elige la operación y el archivo, y la interfaz
// solo entrega un FileOperation al sistema (que crea el proceso de E/S). Antes de entregarlo comprueba
// permisos y estado con FileSystem.check...: si no se puede, explica por qué sin tocar la simulación.
public class FileOperationDialog extends JDialog {

    private final FileOperationRequester requester;
    private final FileSystem fileSystem;
    private final UserSession session;

    private final JComboBox<IoOperation> opBox = new JComboBox<>(new IoOperation[]{
        IoOperation.CREATE, IoOperation.READ, IoOperation.UPDATE, IoOperation.DELETE});
    private final JComboBox<String> pathBox = new JComboBox<>();
    private final JComboBox<FileType> typeBox = new JComboBox<>(FileType.values());
    private final JTextField blocksField = new JTextField("4");
    private final JComboBox<FilePermission> permBox = new JComboBox<>(FilePermission.values());
    private final JComboBox<String> ownerBox = new JComboBox<>();
    private final JTextField newNameField = new JTextField();

    private final JPanel typeRow;
    private final JPanel blocksRow;
    private final JPanel permRow;
    private final JPanel ownerRow;
    private final JPanel newNameRow;

    public FileOperationDialog(Window owner, FileOperationRequester requester, FileSystem fileSystem,
            UserSession session) {
        super(owner, "Operación sobre archivos", ModalityType.APPLICATION_MODAL);
        this.requester = requester;
        this.fileSystem = fileSystem;
        this.session = session;

        FileSystem.EntryInfo[] entries = fileSystem.snapshot();
        for (int i = 0; i < entries.length; i++) {
            pathBox.addItem(entries[i].path());
        }
        pathBox.setEditable(true);

        // Llena los dueños disponibles (admin + usuarios presentes en el árbol, sin usar colecciones de Java)
        ownerBox.addItem("admin");
        String[] foundOwners = new String[entries.length];
        int ownerCount = 0;
        for (int i = 0; i < entries.length; i++) {
            String o = entries[i].owner();
            if ("admin".equals(o)) {
                continue;
            }
            boolean known = false;
            for (int j = 0; j < ownerCount; j++) {
                known |= foundOwners[j].equals(o);
            }
            if (!known) {
                foundOwners[ownerCount++] = o;
                ownerBox.addItem(o);
            }
        }
        if (ownerCount == 0) {
            ownerBox.addItem("user1");
        }

        String who = session.getMode() == UserMode.USER ? "Usuario: " + session.getUser() : "Administrador";
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        form.add(row("Modo activo:", new JLabel(who)));
        form.add(row("Operación:", opBox));
        form.add(row("Ruta (elija o escriba):", pathBox));
        typeRow = row("Crear un:", typeBox);
        blocksRow = row("Tamaño en bloques (1-500):", blocksField);
        permRow = row("Permiso:", permBox);
        ownerRow = row("Dueño:", ownerBox);
        newNameRow = row("Nombre nuevo:", newNameField);
        form.add(typeRow);
        form.add(blocksRow);
        form.add(permRow);
        form.add(ownerRow);
        form.add(newNameRow);

        JButton ok = new JButton("Crear proceso de E/S");
        JButton cancel = new JButton("Cancelar");
        ok.addActionListener(e -> submit());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        opBox.addActionListener(e -> updateVisibleRows());
        typeBox.addActionListener(e -> updateVisibleRows());
        updateVisibleRows();
        setLocationRelativeTo(owner);
    }

    private static JPanel row(String label, JComponent field) {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));
        panel.add(new JLabel(label));
        panel.add(field);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height + 6));
        return panel;
    }

    private void updateVisibleRows() {
        IoOperation op = (IoOperation) opBox.getSelectedItem();
        boolean create = op == IoOperation.CREATE;
        typeRow.setVisible(create);
        blocksRow.setVisible(create && typeBox.getSelectedItem() == FileType.FILE);
        permRow.setVisible(create);
        ownerRow.setVisible(create && session.getMode() == UserMode.ADMINISTRATOR);
        newNameRow.setVisible(op == IoOperation.UPDATE);
        pack();
    }

    private void submit() {
        try {
            IoOperation op = (IoOperation) opBox.getSelectedItem();
            Object edited = pathBox.getEditor().getItem();
            String path = edited == null ? "" : edited.toString().trim();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("La ruta no puede estar vacía");
            }
            UserMode mode = session.getMode();
            String user = mode == UserMode.USER ? session.getUser() : null;
            String owner = (mode == UserMode.USER) ? user
                    : (op == IoOperation.CREATE ? (String) ownerBox.getSelectedItem() : "admin");

            FileType type = null;
            int blocks = 0;
            FilePermission permission = null;
            String newName = null;
            String error;
            switch (op) {
                case CREATE -> {
                    type = (FileType) typeBox.getSelectedItem();
                    permission = (FilePermission) permBox.getSelectedItem();
                    if (type == FileType.FILE) {
                        blocks = readInt(blocksField, "Tamaño en bloques", 1, 500);
                    }
                    error = fileSystem.checkCreate(path, type, blocks, mode, user);
                }
                case READ -> error = fileSystem.checkRead(path, mode, user);
                case UPDATE -> {
                    newName = newNameField.getText().trim();
                    error = fileSystem.checkUpdate(path, newName, mode, user);
                }
                case DELETE -> error = fileSystem.checkDelete(path, mode, user);
                default -> error = "Operación no válida";
            }
            if (error != null) {
                JOptionPane.showMessageDialog(this, "Operación rechazada: " + error,
                        "Operación no permitida", JOptionPane.WARNING_MESSAGE);
                return;
            }
            requester.requestFileOperation(new FileOperation(op, path, type, blocks, permission, newName, owner, mode));
            dispose();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Dato inválido", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static int readInt(JTextField field, String label, int min, int max) {
        String text = field.getText().trim();
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " debe ser un número entero (recibido: '" + text + "')");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + " debe estar entre " + min + " y " + max
                    + " (recibido: " + value + ")");
        }
        return value;
    }
}