package com.avilaos.gui;

import com.avilaos.config.FileSystemConfig;
import com.avilaos.config.FileSystemLoader;
import com.avilaos.config.FileSystemValidator;
import com.avilaos.config.SystemFactory;
import com.avilaos.config.WorkloadConfig;
import com.avilaos.config.WorkloadLoader;
import com.avilaos.config.WorkloadValidator;
import com.avilaos.system.Cluster;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;

// Pantalla de inicio del Proyecto 2. El enunciado exige cargar los DOS JSON antes de iniciar el reloj:
// sin el del sistema de archivos los procesos de E/S fallarían (rutas que no existen) y sin el de procesos
// el disco y la RAM quedarían vacíos. Cada archivo se lee y valida apenas se elige, y el botón de iniciar
// solo se habilita con los dos cargados. Aquí no hay lógica del SO: solo se arma el sistema con las fábricas.
public class StartFrame extends JFrame {

    private FileSystemConfig fsConfig;
    private String fsName;
    private WorkloadConfig workload;
    private String workloadName;

    private final JLabel fsLabel = new JLabel("Sin cargar");
    private final JLabel workloadLabel = new JLabel("Sin cargar");
    private final JButton startButton = new JButton("Iniciar simulación");

    public StartFrame() {
        this(null, null, null, null);
    }

    // Al volver desde la simulación: conserva los archivos que ya estaban cargados
    public StartFrame(FileSystemConfig fsConfig, String fsName, WorkloadConfig workload, String workloadName) {
        super("ÁvilaOS - Proyecto 2: carga de archivos");
        this.fsConfig = fsConfig;
        this.fsName = fsName;
        this.workload = workload;
        this.workloadName = workloadName;
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JButton fsButton = new JButton("Cargar sistema de archivos (JSON)...");
        fsButton.addActionListener(e -> loadFileSystem());
        JPanel fsPanel = new JPanel(new GridLayout(2, 1, 4, 4));
        fsPanel.setBorder(BorderFactory.createTitledBorder("1. Sistema de archivos y disco"));
        fsPanel.add(fsButton);
        fsPanel.add(fsLabel);

        JButton workloadButton = new JButton("Cargar procesos y configuración (JSON)...");
        workloadButton.addActionListener(e -> loadWorkload());
        JPanel workloadPanel = new JPanel(new GridLayout(2, 1, 4, 4));
        workloadPanel.setBorder(BorderFactory.createTitledBorder("2. Procesos y configuración"));
        workloadPanel.add(workloadButton);
        workloadPanel.add(workloadLabel);

        JButton project1Button = new JButton("Proyecto 1 (configuración clásica)");
        project1Button.addActionListener(e -> {
            new ConfigFrame().setVisible(true);
            dispose();
        });
        startButton.addActionListener(e -> startSimulation());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(project1Button);
        buttons.add(startButton);

        JPanel center = new JPanel(new GridLayout(2, 1, 8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        center.add(fsPanel);
        center.add(workloadPanel);

        setLayout(new BorderLayout());
        add(new JLabel("  Carga los dos archivos para poder iniciar la simulación"), BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        updateLabels();
        setSize(620, 330);
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- carga

    private void loadFileSystem() {
        File file = choose();
        if (file == null) {
            return;
        }
        try {
            FileSystemConfig cfg = FileSystemLoader.load(file);
            FileSystemValidator.validate(cfg);
            fsConfig = cfg;
            fsName = file.getName();
            updateLabels();
        } catch (IllegalArgumentException ex) {
            showError("Sistema de archivos: " + ex.getMessage());
        }
    }

    private void loadWorkload() {
        File file = choose();
        if (file == null) {
            return;
        }
        try {
            WorkloadConfig cfg = WorkloadLoader.load(file);
            WorkloadValidator.validate(cfg);
            workload = cfg;
            workloadName = file.getName();
            updateLabels();
        } catch (IllegalArgumentException ex) {
            showError("Procesos y configuración: " + ex.getMessage());
        }
    }

    private File choose() {
        JFileChooser chooser = new JFileChooser(new File("config"));
        chooser.setFileFilter(new FileNameExtensionFilter("Archivos de configuración (*.json)", "json"));
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private void updateLabels() {
        if (fsConfig == null) {
            fsLabel.setText("Sin cargar");
        } else {
            int entries = fsConfig.fileSystem == null ? 0 : fsConfig.fileSystem.length;
            fsLabel.setText(fsName + "  (" + fsConfig.diskConfig.totalBlocks + " bloques, " + entries
                    + " entradas, cabezal en " + fsConfig.diskConfig.initialHead + ")");
        }
        if (workload == null) {
            workloadLabel.setText("Sin cargar");
        } else {
            int processes = workload.processes == null ? 0 : workload.processes.length;
            workloadLabel.setText(workloadName + "  (" + processes + " procesos, RAM " + workload.computer.ramCapacity
                    + ", CPU " + workload.computer.policy + ", disco " + workload.globalConfig.initialDiskPolicy
                    + ", swap " + workload.globalConfig.swapSizeBlocks + ")");
        }
        startButton.setEnabled(fsConfig != null && workload != null);
    }

    // ---------------------------------------------------------------- iniciar

    // Construye el sistema con los dos archivos y abre la ventana principal (el reloj no arranca solo)
    private void startSimulation() {
        try {
            JTextArea logArea = new JTextArea();
            logArea.setEditable(false);
            GuiEventLogger logger = new GuiEventLogger(logArea);
            FileSystemLoader.LoadedFileSystem state = FileSystemLoader.build(fsConfig, workload.globalConfig.swapSizeBlocks);
            Cluster cluster = SystemFactory.build(state, workload, logger);

            final FileSystemConfig keepFs = fsConfig;
            final String keepFsName = fsName;
            final WorkloadConfig keepWorkload = workload;
            final String keepWorkloadName = workloadName;
            new MainFrame(cluster, logArea,
                    (Runnable) () -> new StartFrame(keepFs, keepFsName, keepWorkload, keepWorkloadName).setVisible(true))
                    .setVisible(true);
            dispose();
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error al cargar", JOptionPane.ERROR_MESSAGE);
    }
}
