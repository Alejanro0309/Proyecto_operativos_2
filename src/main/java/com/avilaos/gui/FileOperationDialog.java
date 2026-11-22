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

// Pide una operación sobre un archivo (crear, leer, actualizar o eliminar). La interfaz NUNCA la hace:
// primero le pregunta a FileSystem si el modo y el usuario activos la permiten (y, si no, explica por qué
// sin interrumpir la simulación); si sí, le entrega la orden a un FileOperationRequester, que crea el proceso
// de E/S. Valida tipo de dato y rango de todos los campos antes de pedir nada.
public class FileOperationDialog extends JDialog {

    private static final int MAX_BLOCKS = 10000;

    private final FileSystem fileSystem;
    private final UserSession session;
    private final FileOperationRequester requester;

    private final JComboBox<IoOperation> operationBox = new JComboBox<>(new IoOperation[]{
        IoOperation.CREATE, IoOperation.READ, IoOperation.UPDATE, IoOperation.DELETE});
    private final JComboBox<String> existingPathBox = new JComboBox<>();
    private final JTextField newPathField = new JTextField("/");
    private final JComboBox<FileType> typeBox = new JComboBox<>(FileType.values());
    private final JTextField blocksField = new JTextField("4");
    private final JComboBox<FilePermission> permissionBox = new JComboBox<>(FilePermission.values());
    private final JTextField newNameField = new JTextField();

    // filas que aparecen o se ocultan según la operación
    private final JPanel existingPathRow;
    private final JPanel newPathRow;
    private final JPanel typeRow;
    private final JPanel blocksRow;
    private final JPanel permissionRow;
    private final JPanel newNameRow;

    public FileOperationDialog(Window owner, FileSystem fileSystem, UserSession session,
            FileOperationRequester requester) {
        super(owner, "Operación sobre un archivo", ModalityType.APPLICATION_MODAL);
        this.fileSystem = fileSystem;
        this.session = session;
        this.requester = requester;

        FileSystem.EntryInfo[] entries = fileSystem.snapshot();
        for (int i = 0; i < entries.length; i++) {
            existingPathBox.addItem(entries[i].path());
        }

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel who = new JLabel("Modo: " + session.getMode()
                + (session.getMode() == UserMode.USER ? "   Usuario: " + session.getUser() : ""));
        who.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        form.add(who);
        form.add(row("Operación:", operationBox));
        existingPathRow = row("Archivo o directorio:", existingPathBox);
        newPathRow = row("Ruta nueva (ej. /usuarios/a.txt):", newPathField);
        typeRow = row("Tipo:", typeBox);
        blocksRow = row("Tamaño en bloques (1-" + MAX_BLOCKS + "):", blocksField);
        permissionRow = row("Permiso:", permissionBox);
        newNameRow = row("Nombre nuevo:", newNameField);
        form.add(existingPathRow);
        form.add(newPathRow);
        form.add(typeRow);
        form.add(blocksRow);
        form.add(permissionRow);
        form.add(newNameRow);

        JButton create = new JButton("Crear proceso de E/S");
        JButton cancel = new JButton("Cancelar");
        create.addActionListener(e -> submit());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(create);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        operationBox.addActionListener(e -> updateVisibleRows());
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

    // BoxLayout no reserva espacio para componentes ocultos, por eso se usa setVisible
    private void updateVisibleRows() {
        IoOperation op = (IoOperation) operationBox.getSelectedItem();
        boolean create = op == IoOperation.CREATE;
        existingPathRow.setVisible(!create);
        newPathRow.setVisible(create);
        typeRow.setVisible(create);
        blocksRow.setVisible(create && typeBox.getSelectedItem() == FileType.FILE);
        permissionRow.setVisible(create);
        newNameRow.setVisible(op == IoOperation.UPDATE);
        pack();
    }

    private void submit() {
        try {
            IoOperation op = (IoOperation) operationBox.getSelectedItem();
            UserMode mode = session.getMode();
            String user = session.getUser();
            String owner = mode == UserMode.USER ? user : "admin";
            String reason;
            FileOperation request;
            String path;
            switch (op) {
                case CREATE: {
                    path = newPathField.getText().trim();
                    FileType type = (FileType) typeBox.getSelectedItem();
                    int blocks = type == FileType.FILE ? readInt(blocksField, "Tamaño en bloques", 1, MAX_BLOCKS) : 0;
                    FilePermission permission = (FilePermission) permissionBox.getSelectedItem();
                    reason = fileSystem.checkCreate(path, type, blocks, mode, user);
                    request = new FileOperation(op, path, type, blocks, permission, null, owner);
                    break;
                }
                case READ:
                    path = selectedExistingPath();
                    reason = fileSystem.checkRead(path, mode, user);
                    request = new FileOperation(op, path, null, 0, null, null, owner);
                    break;
                case UPDATE: {
                    path = selectedExistingPath();
                    String newName = newNameField.getText().trim();
                    reason = fileSystem.checkUpdate(path, newName, mode, user);
                    request = new FileOperation(op, path, null, 0, null, newName, owner);
                    break;
                }
                case DELETE:
                    path = selectedExistingPath();
                    reason = fileSystem.checkDelete(path, mode, user);
                    request = new FileOperation(op, path, null, 0, null, null, owner);
                    break;
                default:
                    throw new IllegalArgumentException("Operación no válida");
            }
            if (reason != null) {
                JOptionPane.showMessageDialog(this, reason, "Operación rechazada", JOptionPane.WARNING_MESSAGE);
                return;
            }
            requester.requestFileOperation(request);
            dispose();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Dato inválido", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String selectedExistingPath() {
        Object selected = existingPathBox.getSelectedItem();
        if (selected == null) {
            throw new IllegalArgumentException("Elige un archivo o directorio");
        }
        return selected.toString();
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
