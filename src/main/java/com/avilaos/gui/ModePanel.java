package com.avilaos.gui;

import com.avilaos.enums.UserMode;
import com.avilaos.filesystem.FileSystem;
import java.awt.FlowLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

// Selector del modo de uso (administrador o usuario) y, en modo usuario, del usuario. Es el
// "modo de usuario activo" que el enunciado pide mostrar. Los usuarios disponibles salen de los
// dueños que aparecen en el sistema de archivos (menos 'admin').
public class ModePanel extends JPanel implements UserSession {

    private final JComboBox<UserMode> modeBox = new JComboBox<>(UserMode.values());
    private final JComboBox<String> userBox;

    public ModePanel(FileSystem fileSystem) {
        String[] users = ownersOf(fileSystem);
        userBox = new JComboBox<>(users);
        userBox.setEnabled(false);
        modeBox.addActionListener(e -> userBox.setEnabled(modeBox.getSelectedItem() == UserMode.USER));

        setLayout(new FlowLayout(FlowLayout.LEFT));
        add(new JLabel("Modo:"));
        add(modeBox);
        add(new JLabel("Usuario:"));
        add(userBox);
    }

    @Override
    public UserMode getMode() {
        return (UserMode) modeBox.getSelectedItem();
    }

    @Override
    public String getUser() {
        return (String) userBox.getSelectedItem();
    }

    private static String[] ownersOf(FileSystem fileSystem) {
        FileSystem.EntryInfo[] entries = fileSystem.snapshot();
        String[] found = new String[entries.length + 2];
        int count = 0;

        // Garantizamos siempre user1 y user2 disponibles en el selector
        found[count++] = "user1";
        found[count++] = "user2";

        for (int i = 0; i < entries.length; i++) {
            String owner = entries[i].owner();
            if (owner == null || owner.equals("admin")) {
                continue;
            }
            boolean known = false;
            for (int j = 0; j < count; j++) {
                known |= found[j].equals(owner);
            }
            if (!known) {
                found[count++] = owner;
            }
        }

        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = found[i];
        }
        return result;
    }
}
