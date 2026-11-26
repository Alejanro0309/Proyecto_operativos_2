package com.avilaos.gui;

import com.avilaos.config.FileSystemLoader;
import com.avilaos.disk.DiskStatus;
import com.avilaos.enums.UserMode;
import com.avilaos.filesystem.FileOperation;
import com.avilaos.model.ProcessControlBlock;
import com.avilaos.sync.BoundedBuffer;
import com.avilaos.sync.BufferStatus;
import com.avilaos.system.Cluster;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

// Parte global de la ventana: control del reloj, velocidad, alta de procesos y buffers,
// tabla de buffers y log de eventos.
//
// En el Proyecto 2 (el clúster tiene sistema de archivos): el botón de procesos abre el diálogo de
// operaciones sobre archivos, aparece el selector de modo (administrador / usuario), el botón para
// guardar el sistema de archivos en JSON y los botones de pruebas (operaciones y directorios al azar,
// siempre válidos para el modo activo). Se ocultan los buffers y los procesos aleatorios del Proyecto 1.
// La simulación se pausa sola cuando ya no queda ningún proceso activo ni solicitud en el disco (así las
// métricas y la gráfica no se distorsionan con ciclos vacíos) y se reanuda sola al crear una operación.
//
// Solo ENVÍA órdenes al Cluster y LEE copias seguras. No decide nada del sistema.
public class GlobalPanel extends JPanel {

    // GlobalClock no se puede reiniciar: tras stop() sus hilos mueren (ver ClockWorker.stop).
    // Por eso DETENIDA es un estado final.
    private enum ClockState { SIN_INICIAR, EN_EJECUCION, EN_PAUSA, DETENIDA }

    private static final String[] BUFFER_COLUMNS = {"Buffer", "Anfitrión", "Elementos",
        "s (mutex)", "n (elementos)", "e (espacios)",
        "Esperan en s", "Esperan en n", "Esperan en e", "Producidos", "Consumidos"};

    private final Cluster cluster;
    private final boolean p2;
    private ClockState state = ClockState.SIN_INICIAR;
    private boolean autoPaused;          // la pausa la puso el sistema (sin trabajo), no el usuario

    private final JLabel cycleLabel = new JLabel("Ciclo actual: 0");
    private final JLabel stateLabel = new JLabel();
    private final JLabel latencyLabel = new JLabel();
    private final JTextField speedField = new JTextField(5);
    private final JButton startButton = new JButton("Iniciar");
    private final JButton pauseButton = new JButton("Pausar");
    private final JButton resumeButton = new JButton("Reanudar");
    private final JButton stopButton = new JButton("Finalizar simulación");
    private final JButton backButton = new JButton("Volver a la configuración");
    private Runnable onBackToConfig;
    private final JButton processButton = new JButton("Crear proceso...");
    private final JButton bufferButton = new JButton("Crear buffer...");
    private final JButton randomOneButton = new JButton("Proceso aleatorio");
    private final JButton randomFiveButton = new JButton("5 aleatorios");
    private final JButton randomDirButton = new JButton("Directorio aleatorio");
    private final JButton saveFsButton = new JButton("Guardar sistema de archivos...");

    // Proyecto 2: selector de modo de usuario y generador de operaciones al azar (null en el Proyecto 1)
    private ModePanel modePanel;
    private RandomFileOperationFactory randomFactory;

    private final DefaultTableModel buffersModel = new DefaultTableModel(new Object[0][0], BUFFER_COLUMNS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    public GlobalPanel(Cluster cluster, JTextArea logArea) {
        this.cluster = cluster;
        this.p2 = cluster.getFileSystem() != null;
        speedField.setText(String.valueOf(cluster.getClock().getCycleDurationMs()));

        startButton.addActionListener(e -> {
            cluster.start();
            autoPaused = false;
            setState(ClockState.EN_EJECUCION);
        });
        pauseButton.addActionListener(e -> {
            cluster.pause();
            autoPaused = false;
            setState(ClockState.EN_PAUSA);
        });
        resumeButton.addActionListener(e -> {
            cluster.resume();
            autoPaused = false;
            setState(ClockState.EN_EJECUCION);
        });
        stopButton.addActionListener(e -> confirmStop());
        backButton.addActionListener(e -> confirmBack());
        JButton speedButton = new JButton("Aplicar");

        speedButton.addActionListener(e -> applySpeed());
        if (p2) {
            modePanel = new ModePanel(cluster.getFileSystem());
            randomFactory = new RandomFileOperationFactory(cluster.getFileSystem(), modePanel);
            processButton.setText("Operación de archivo...");
            processButton.addActionListener(e -> {
                new FileOperationDialog(SwingUtilities.getWindowAncestor(this), cluster,
                        cluster.getFileSystem(), modePanel).setVisible(true);
                resumeIfAutoPaused();
            });
            saveFsButton.addActionListener(e -> saveFileSystem());
            randomOneButton.setText("Operación aleatoria");
            randomFiveButton.setText("5 operaciones aleatorias");
            randomOneButton.setToolTipText("Crea un proceso de E/S al azar, válido para el modo y usuario activos");
            randomFiveButton.setToolTipText("Crea 5 procesos de E/S al azar, válidos para el modo y usuario activos");
            randomDirButton.setToolTipText("Crea un directorio al azar (solo el administrador crea directorios)");
            randomOneButton.addActionListener(e -> addRandomFileOperations(1));
            randomFiveButton.addActionListener(e -> addRandomFileOperations(5));
            randomDirButton.addActionListener(e -> addRandomDirectory());
        } else {
            processButton.addActionListener(e ->
                    new ProcessDialog(SwingUtilities.getWindowAncestor(this), cluster).setVisible(true));
            randomOneButton.addActionListener(e -> addRandomProcesses(1));
            randomFiveButton.addActionListener(e -> addRandomProcesses(5));
        }
        bufferButton.addActionListener(e ->
                new BufferDialog(SwingUtilities.getWindowAncestor(this), cluster).setVisible(true));

        JPanel clockRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        clockRow.add(cycleLabel);
        clockRow.add(stateLabel);
        if (!p2) {
            clockRow.add(latencyLabel);
        }
        clockRow.add(startButton);
        clockRow.add(pauseButton);
        clockRow.add(resumeButton);
        clockRow.add(stopButton);
        clockRow.add(new JLabel("  Duración del ciclo (ms):"));
        clockRow.add(speedField);
        clockRow.add(speedButton);
        clockRow.add(backButton);

        JPanel createRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        createRow.add(processButton);
        if (p2) {
            createRow.add(modePanel);
            createRow.add(saveFsButton);
        } else {
            createRow.add(bufferButton);
            createRow.add(new JLabel("  Pruebas:"));
            createRow.add(randomOneButton);
            createRow.add(randomFiveButton);
        }

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(clockRow);
        controls.add(createRow);
        if (p2) {
            JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
            testRow.add(new JLabel("Pruebas:"));
            testRow.add(randomOneButton);
            testRow.add(randomFiveButton);
            testRow.add(randomDirButton);
            controls.add(testRow);
        }

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log de eventos"));

        setLayout(new BorderLayout());
        add(controls, BorderLayout.NORTH);
        if (p2) {
            // sin buffers en el Proyecto 2: el log ocupa todo el ancho
            add(logScroll, BorderLayout.CENTER);
        } else {
            JTable buffersTable = new JTable(buffersModel);
            buffersTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
            buffersTable.setFillsViewportHeight(true);
            JScrollPane buffersScroll = new JScrollPane(buffersTable);
            buffersScroll.setBorder(BorderFactory.createTitledBorder("Buffers (semáforos y procesos en espera)"));
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buffersScroll, logScroll);
            split.setResizeWeight(0.55);
            add(split, BorderLayout.CENTER);
        }
        setState(ClockState.SIN_INICIAR);
    }

    // ---------------------------------------------------------------- órdenes

    // Operaciones al azar: el generador ya comprobó que el modo activo puede hacerlas
    private void addRandomFileOperations(int count) {
        FileOperation[] operations = randomFactory.generateFileOperations(count);
        if (operations.length == 0) {
            logNothingToGenerate();
            return;
        }
        submitAll(operations);
    }

    // Directorio al azar: solo el administrador crea directorios
    private void addRandomDirectory() {
        FileOperation operation = randomFactory.generateDirectory();
        if (operation == null) {
            logNothingToGenerate();
            return;
        }
        submitAll(new FileOperation[]{operation});
    }

    private void submitAll(FileOperation[] operations) {
        for (int i = 0; i < operations.length; i++) {
            try {
                cluster.requestFileOperation(operations[i]);
            } catch (IllegalArgumentException ex) {
                error(ex.getMessage());
                return;
            }
        }
        resumeIfAutoPaused();
    }

    private void logNothingToGenerate() {
        cluster.getLogger().log(cluster.getCurrentCycle(), 0,
                "No hay ninguna operación aleatoria posible para el modo y usuario activos");
    }

    // Guarda el estado actual del disco (directorios, archivos, bloques de cada uno, dueños, permisos y
    // posición del cabezal) en un JSON que se puede cargar en otra ejecución. Es solo lectura del sistema.
    private void saveFileSystem() {
        JFileChooser chooser = new JFileChooser(new File("config"));
        chooser.setFileFilter(new FileNameExtensionFilter("Sistema de archivos de ÁvilaOS (*.json)", "json"));
        chooser.setSelectedFile(new File("sistema_archivos_guardado.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().endsWith(".json")) {
            file = new File(file.getPath() + ".json");
        }
        try {
            FileSystemLoader.save(cluster.getFileSystem(), cluster.getDisk().getTotalBlocks(),
                    cluster.getDisk().snapshot().headPosition(), file);
            JOptionPane.showMessageDialog(this, "Sistema de archivos guardado en " + file.getName(),
                    "Guardado", JOptionPane.INFORMATION_MESSAGE);
        } catch (IllegalArgumentException ex) {
            error(ex.getMessage());
        }
    }

    private void confirmStop() {
        int answer = JOptionPane.showConfirmDialog(this,
                 "Finalizar es definitivo: el reloj no se puede reiniciar en esta ventana.\n"
                + "Los resultados quedan visibles en las pestañas. Para otra simulación,\n"
                + "use \"Volver a la configuración\". ¿Finalizar?",
                "Finalizar simulación", JOptionPane.YES_NO_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            cluster.stop();
            setState(ClockState.DETENIDA);
        }
    }

    // MainFrame decide qué hacer al volver (cerrar esta ventana y abrir la configuración)
    public void setOnBackToConfig(Runnable action) {
        this.onBackToConfig = action;
    }

    private void confirmBack() {
        if (onBackToConfig == null) {
            return;
        }
        if (state == ClockState.EN_EJECUCION || state == ClockState.EN_PAUSA) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "La simulación actual se detendrá y sus resultados se perderán.\n"
                    + "¿Volver a la configuración?",
                    "Volver a la configuración", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        onBackToConfig.run();
    }

    // La duración se puede cambiar con el reloj corriendo (GlobalClock.cycleDurationMs es volatile)
    private void applySpeed() {
        int ms;
        try {
            ms = Integer.parseInt(speedField.getText().trim());
        } catch (NumberFormatException e) {
            error("La duración del ciclo debe ser un número entero entre 0 y 10000");
            return;
        }
        if (ms < 0 || ms > 10000) {
            error("La duración del ciclo debe estar entre 0 y 10000 ms (recibido: " + ms + ")");
            return;
        }
        try {
            cluster.setCycleDurationMs(ms);
        } catch (IllegalArgumentException ex) {
            error(ex.getMessage());
        }
    }

    // Solo para pruebas (Proyecto 1): manda procesos con valores al azar usando la misma orden submit
    private void addRandomProcesses(int count) {
        for (int i = 0; i < count; i++) {
            try {
                cluster.submit(RandomProcessFactory.create(cluster));
            } catch (IllegalArgumentException ex) {
                error(ex.getMessage());
                return;
            }
        }
    }

    private void setState(ClockState newState) {
        state = newState;
        startButton.setEnabled(state == ClockState.SIN_INICIAR);
        pauseButton.setEnabled(state == ClockState.EN_EJECUCION);
        resumeButton.setEnabled(state == ClockState.EN_PAUSA);
        stopButton.setEnabled(state == ClockState.EN_EJECUCION || state == ClockState.EN_PAUSA);
        boolean canCreate = state != ClockState.DETENIDA;
        processButton.setEnabled(canCreate);
        bufferButton.setEnabled(canCreate);
        randomOneButton.setEnabled(canCreate);
        randomFiveButton.setEnabled(canCreate);
        stateLabel.setText("  Estado: " + state.name().replace('_', ' ').toLowerCase()
                + (state == ClockState.EN_PAUSA && autoPaused ? " (sin trabajo pendiente)" : "") + "  ");
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Dato inválido", JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------- refresco (Timer de MainFrame)

    public void refresh() {
        cycleLabel.setText("Ciclo actual: " + cluster.getCurrentCycle());
        latencyLabel.setText("  Latencia de red: " + cluster.getNetworkLatency() + " ciclos  ");
        if (p2) {
            // en modo usuario no se crean directorios: el botón se apaga en vez de mostrar un rechazo
            randomDirButton.setEnabled(state != ClockState.DETENIDA && modePanel.getMode() == UserMode.ADMINISTRATOR);
            pauseIfNothingLeftToDo();
            return;                  // Proyecto 2: no hay tabla de buffers que refrescar
        }

        // snapshot() entrega una copia inmutable del buffer; aquí no se toca el buffer real
        Object[] raw = cluster.getBuffersSnapshot();
        buffersModel.setRowCount(0);
        for (int i = 0; i < raw.length; i++) {
            BufferStatus st = ((BoundedBuffer) raw[i]).snapshot();
            buffersModel.addRow(new Object[]{st.id(), "PC" + st.hostComputerId(),
                st.count() + " / " + st.capacity(),
                st.valueS(), st.valueN(), st.valueE(),
                names(st.waitingS()), names(st.waitingN()), names(st.waitingE()),
                st.produced(), st.consumed()});
        }
    }

    // Proyecto 2: cuando no queda ningún proceso vivo (ni suspendido) y el disco está libre y sin cola,
    // el reloj se pausa solo. Al crear una operación nueva se reanuda solo (resumeIfAutoPaused).
    private void pauseIfNothingLeftToDo() {
        if (state != ClockState.EN_EJECUCION || cluster.getCurrentCycle() <= 0) {
            return;
        }
        DiskStatus disk = cluster.getDisk().snapshot();
        boolean idle = cluster.getComputer(0).getKernel().getActiveProcessCount() == 0
                && !disk.busy() && disk.queue().length == 0;
        if (idle) {
            cluster.pause();
            autoPaused = true;
            setState(ClockState.EN_PAUSA);
            cluster.getLogger().log(cluster.getCurrentCycle(), 0,
                    "Simulación en pausa: no quedan procesos activos ni solicitudes en el disco");
        }
    }

    // Si el sistema se pausó solo por falta de trabajo y ahora hay un proceso nuevo, continúa solo.
    // Una pausa puesta por el usuario no se toca.
    private void resumeIfAutoPaused() {
        if (autoPaused && state == ClockState.EN_PAUSA
                && cluster.getComputer(0).getKernel().getActiveProcessCount() > 0) {
            autoPaused = false;
            cluster.resume();
            setState(ClockState.EN_EJECUCION);
            cluster.getLogger().log(cluster.getCurrentCycle(), 0, "Simulación reanudada: hay trabajo nuevo");
        }
    }

    // "P3 Sensor, P5 Monitor" o "—" si no espera nadie
    private static String names(Object[] waiting) {
        if (waiting.length == 0) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < waiting.length; i++) {
            ProcessControlBlock p = (ProcessControlBlock) waiting[i];
            sb.append(i == 0 ? "" : ", ").append("P").append(p.getId()).append(" ").append(p.getName());
        }
        return sb.toString();
    }
}