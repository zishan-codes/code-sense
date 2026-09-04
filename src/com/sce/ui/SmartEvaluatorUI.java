package com.sce.ui;

import com.sce.SystemController;
import com.sce.core.EvaluationRequest;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SmartEvaluatorUI
 *
 * Pure Core Java Swing front-end for the Smart Code Evaluator & AI
 * Debugger. Presents a dark, IDE-style editor with line numbers and
 * lightweight keyword highlighting, submits evaluation requests to the
 * EXISTING SystemController, and renders results via the
 * EvaluationCallback interface without altering any backend logic.
 *
 * This class owns NO evaluation logic itself — it is purely a
 * presentation layer over SystemController's existing queue-based
 * pipeline. All compilation, execution, and AI diagnostic work continues
 * to happen exactly as already implemented in com.sce.engine and
 * com.sce.ai; this class only submits requests and displays results.
 *
 * @author Smart Code Evaluator Team
 */
public class SmartEvaluatorUI extends JFrame implements EvaluationCallback {

    // ---------------------------------------------------------------
    // Color theme — matches the dark developer-tool palette specified
    // for this project. Centralized here so all components draw from
    // a single, consistent source rather than scattered literals.
    // ---------------------------------------------------------------
    private static final Color EDITOR_BG          = new Color(0x2B2B2B);
    private static final Color TEXT_COLOR          = new Color(0xA9B7C6);
    private static final Color PANEL_BG            = new Color(0x3C3F41);
    private static final Color ACCENT_COLOR        = new Color(0xCC7832);
    private static final Color AI_HINT_COLOR       = new Color(0x6A8759);
    private static final Color AI_DIAGNOSTIC_BG    = new Color(0x323232);
    private static final Color BORDER_COLOR        = new Color(0x1E1F22);
    private static final Color LINE_NUMBER_BG      = new Color(0x313335);
    private static final Color LINE_NUMBER_FG      = new Color(0x606366);

    private static final Font EDITOR_FONT = resolveEditorFont();

    private static final String DEFAULT_SAMPLE_CODE =
            "public class Main {\n" +
            "    public static void main(String[] args) {\n" +
            "        int numerator = 10;\n" +
            "        int denominator = 2;\n" +
            "        System.out.println(\"Result: \" + (numerator / denominator));\n" +
            "    }\n" +
            "}\n";

    /** Extracts the public class name from source text so an
     *  EvaluationRequest can be constructed without asking the user to
     *  type the class name separately. Falls back to "Main" if no
     *  public class declaration is found, consistent with
     *  EvaluationRequest's requirement of a non-blank className. */
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("public\\s+class\\s+(\\w+)");

    /** Java keywords highlighted in ACCENT_COLOR within the editor. */
    private static final String[] JAVA_KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null"
    };

    private static final Pattern KEYWORD_PATTERN = buildKeywordPattern();

    // ---------------------------------------------------------------
    // Backend reference — the EXISTING controller, untouched.
    // ---------------------------------------------------------------
    private final SystemController controller;

    // ---------------------------------------------------------------
    // Swing components
    // ---------------------------------------------------------------
    private JTextPane codeEditor;
    private LineNumberPanel lineNumberPanel;
    private JTextArea consoleOutputArea;
    private JTextArea aiHintArea;
    private JTabbedPane outputTabs;
    private JButton runButton;
    private JLabel statusLabel;

    private SimpleAttributeSet normalAttributeSet;
    private SimpleAttributeSet keywordAttributeSet;

    /** Debounces syntax re-highlighting so it runs once shortly after
     *  typing pauses, rather than on every single keystroke. */
    private final Timer highlightDebounceTimer = new Timer(150, e -> highlightSyntax());

    /** Guards against duplicate submissions from rapid double-clicks or
     *  stray key-triggered activations of the Run button. */
    private volatile boolean isEvaluating = false;

    /**
     * EvaluationCallback
     *
     * Contract through which the (unchanged) SystemController reports
     * evaluation lifecycle events back to whatever is listening — in
     * this sprint, this Swing UI. Every method here may be invoked from
     * a background worker thread; implementations MUST marshal any
     * Swing updates onto the Event Dispatch Thread themselves.
     */

    /**
     * Constructs the UI and wires it to the given, already-constructed
     * SystemController. Registers this UI instance as the controller's
     * EvaluationCallback so pipeline results are routed here.
     *
     * @param controller the existing, already-started SystemController
     *                    instance to submit evaluations through
     */
    public SmartEvaluatorUI(SystemController controller) {
        super("Smart Code Evaluator & AI Debugger");
        this.controller = controller;

        initializeStyledAttributes();
        buildUI();

        // Registers this UI as the recipient of all future evaluation
        // lifecycle events. The controller itself is never modified
        // beyond exposing this setter — see accompanying diff.
        this.controller.setCallback(this);
    }

    // =================================================================
    // UI CONSTRUCTION
    // =================================================================

    private void buildUI() {
        setSize(1000, 700);
        setMinimumSize(new Dimension(800, 550));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(EDITOR_BG);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Shuts the backend down gracefully (worker thread +
                // ExecutionEngine's pool) before the process exits.
                controller.shutdown();
                dispose();
                System.exit(0);
            }
        });

        add(buildHeaderPanel(), BorderLayout.NORTH);
        add(buildMainSplitPane(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JPanel buildHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PANEL_BG);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)
        ));

        JLabel titleLabel = new JLabel("Smart Code Evaluator & AI Debugger");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        titleLabel.setForeground(TEXT_COLOR);

        runButton = new JButton("Run Evaluation");
        styleRunButton(runButton);
        runButton.addActionListener(this::onRunClicked);

        header.add(titleLabel, BorderLayout.WEST);
        header.add(runButton, BorderLayout.EAST);
        return header;
    }

    private void styleRunButton(JButton button) {
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBackground(AI_HINT_COLOR);
        button.setForeground(Color.WHITE);
        button.setFont(new Font("SansSerif", Font.BOLD, 13));
        button.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private JSplitPane buildMainSplitPane() {
        codeEditor = new JTextPane();
        codeEditor.setFont(EDITOR_FONT);
        codeEditor.setBackground(EDITOR_BG);
        codeEditor.setForeground(TEXT_COLOR);
        codeEditor.setCaretColor(TEXT_COLOR);
        codeEditor.setSelectionColor(new Color(0x214283));
        codeEditor.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        codeEditor.setText(DEFAULT_SAMPLE_CODE);

        codeEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onDocumentEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onDocumentEdited(); }
            @Override public void changedUpdate(DocumentEvent e) {
                // Fired by our OWN setCharacterAttributes() calls inside
                // highlightSyntax(). Intentionally ignored — reacting here
                // would re-trigger highlighting in an infinite loop.
            }
        });

        lineNumberPanel = new LineNumberPanel();

        JScrollPane editorScrollPane = new JScrollPane(codeEditor);
        editorScrollPane.setRowHeaderView(lineNumberPanel);
        editorScrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        editorScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, editorScrollPane, buildOutputTabs()
        );
        splitPane.setResizeWeight(0.65);
        splitPane.setDividerSize(4);
        splitPane.setBorder(null);
        splitPane.setBackground(PANEL_BG);

        // Trigger the initial highlight pass on the sample code so the
        // editor doesn't open with plain, unstyled text.
        SwingUtilities.invokeLater(this::highlightSyntax);

        return splitPane;
    }

    private JTabbedPane buildOutputTabs() {
        consoleOutputArea = new JTextArea();
        consoleOutputArea.setEditable(false);
        consoleOutputArea.setBackground(EDITOR_BG);
        consoleOutputArea.setForeground(TEXT_COLOR);
        consoleOutputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        consoleOutputArea.setCaretColor(TEXT_COLOR);
        consoleOutputArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane consoleScroll = new JScrollPane(consoleOutputArea);
        consoleScroll.setBorder(null);

        aiHintArea = new JTextArea();
        aiHintArea.setEditable(false);
        aiHintArea.setLineWrap(true);
        aiHintArea.setWrapStyleWord(true);
        aiHintArea.setBackground(AI_DIAGNOSTIC_BG);
        aiHintArea.setForeground(AI_HINT_COLOR);
        aiHintArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        aiHintArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane aiScroll = new JScrollPane(aiHintArea);
        aiScroll.setBorder(null);

        outputTabs = new JTabbedPane();
        outputTabs.setBackground(PANEL_BG);
        outputTabs.setForeground(TEXT_COLOR);
        outputTabs.addTab("Console Output", consoleScroll);
        outputTabs.addTab("AI Diagnostic Hint", aiScroll);
        return outputTabs;
    }

    private JPanel buildStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(PANEL_BG);
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)
        ));
        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(TEXT_COLOR);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusBar.add(statusLabel, BorderLayout.WEST);
        return statusBar;
    }

    // =================================================================
    // RUN BUTTON HANDLING
    // =================================================================

    /**
     * Handles the Run Evaluation button click. Extracts the class name,
     * builds an EvaluationRequest, and submits it to the EXISTING
     * SystemController via its actual, unchanged handleSubmission()
     * method. Returns immediately — SystemController's own worker thread
     * (already implemented) performs the actual compile/execute/AI-hint
     * pipeline asynchronously, so this method never blocks the EDT.
     */
    private void onRunClicked(ActionEvent event) {
        if (isEvaluating) {
            // Defensive guard: prevents a second submission from slipping
            // through in the brief window before the button visually
            // reflects its disabled state.
            return;
        }

        isEvaluating = true;
        runButton.setEnabled(false);
        runButton.setText("Evaluating...");
        consoleOutputArea.setText("");
        aiHintArea.setText("");
        statusLabel.setText("Submitting evaluation...");

        String sourceCode = codeEditor.getText();
        String className = extractClassName(sourceCode);

        try {
            EvaluationRequest request = new EvaluationRequest(className, sourceCode);
            controller.handleSubmission(request);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // EvaluationRequest rejects blank source; handleSubmission
            // rejects submission if the controller was never started.
            // Both are surfaced to the user instead of silently failing,
            // and the button is restored so the UI does not lock up.
            onConsoleOutput("Submission rejected: " + ex.getMessage(), true);
            onEvaluationComplete();
        }
    }

    private String extractClassName(String sourceCode) {
        Matcher matcher = CLASS_NAME_PATTERN.matcher(sourceCode);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "Main";
    }

    // =================================================================
    // EvaluationCallback IMPLEMENTATION
    // (All updates marshaled onto the EDT — safe even though
    // SystemController already wraps its calls in invokeLater as well.)
    // =================================================================

    @Override
    public void onStatusUpdate(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }

    @Override
    public void onConsoleOutput(String text, boolean isError) {
        SwingUtilities.invokeLater(() -> {
            String prefix = isError ? "[ERROR] " : "";
            consoleOutputArea.append(prefix + text + "\n");
            consoleOutputArea.setCaretPosition(consoleOutputArea.getDocument().getLength());
        });
    }

    @Override
    public void onAIHintReceived(String hint, String suggestedFix) {
        SwingUtilities.invokeLater(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Root Cause:\n").append(hint == null ? "" : hint);
            if (suggestedFix != null && !suggestedFix.isBlank()) {
                sb.append("\n\nSuggested Fix:\n").append(suggestedFix);
            }
            aiHintArea.setText(sb.toString());
            outputTabs.setSelectedIndex(1);
        });
    }

    @Override
    public void onEvaluationComplete() {
        SwingUtilities.invokeLater(() -> {
            isEvaluating = false;
            runButton.setEnabled(true);
            runButton.setText("Run Evaluation");
            statusLabel.setText("Ready");
        });
    }

    // =================================================================
    // SYNTAX HIGHLIGHTING
    // =================================================================

    private void initializeStyledAttributes() {
        normalAttributeSet = new SimpleAttributeSet();
        StyleConstants.setForeground(normalAttributeSet, TEXT_COLOR);
        StyleConstants.setBold(normalAttributeSet, false);

        keywordAttributeSet = new SimpleAttributeSet();
        StyleConstants.setForeground(keywordAttributeSet, ACCENT_COLOR);
        StyleConstants.setBold(keywordAttributeSet, true);
    }

    private void onDocumentEdited() {
        highlightDebounceTimer.restart();
        SwingUtilities.invokeLater(() -> {
            lineNumberPanel.revalidate();
            lineNumberPanel.repaint();
        });
    }

    /**
     * Re-applies keyword coloring across the entire document. Resets
     * every character to the normal attribute set first (so edited or
     * deleted keywords do not retain stale coloring), then re-applies
     * the keyword attribute set to every current match.
     *
     * setCharacterAttributes() fires only a "changed" document event
     * (not insert/remove), which our DocumentListener explicitly ignores
     * above — this is what prevents this method from re-triggering itself.
     */
    private void highlightSyntax() {
        StyledDocument doc = codeEditor.getStyledDocument();
        String text;
        try {
            text = doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            // Should not occur since we read exactly [0, doc.getLength()),
            // but if the document changed concurrently, skip this pass
            // rather than risk a corrupted highlight state.
            return;
        }

        doc.setCharacterAttributes(0, text.length(), normalAttributeSet, true);

        Matcher matcher = KEYWORD_PATTERN.matcher(text);
        while (matcher.find()) {
            doc.setCharacterAttributes(
                    matcher.start(), matcher.end() - matcher.start(), keywordAttributeSet, true
            );
        }
    }

    private static Pattern buildKeywordPattern() {
        StringBuilder sb = new StringBuilder("\\b(");
        for (int i = 0; i < JAVA_KEYWORDS.length; i++) {
            sb.append(Pattern.quote(JAVA_KEYWORDS[i]));
            if (i < JAVA_KEYWORDS.length - 1) {
                sb.append("|");
            }
        }
        sb.append(")\\b");
        return Pattern.compile(sb.toString());
    }

    private static Font resolveEditorFont() {
        // "Consolas" is not guaranteed to exist outside Windows; fall
        // back to the platform's generic monospaced font rather than
        // silently rendering with a proportional font on Linux/macOS.
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (family.equalsIgnoreCase("Consolas")) {
                return new Font("Consolas", Font.PLAIN, 15);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 15);
    }

    // =================================================================
    // LINE NUMBER GUTTER
    // =================================================================

    /**
     * LineNumberPanel
     *
     * Custom row-header component for the editor's JScrollPane. Paints
     * right-aligned line numbers whose vertical position tracks the
     * actual text line positions in codeEditor, so numbering stays
     * correct even with variable-height lines or scrolling.
     */
    private final class LineNumberPanel extends JComponent {

        private static final int LEFT_PADDING = 10;
        private static final int RIGHT_PADDING = 8;

        private LineNumberPanel() {
            setFont(EDITOR_FONT);
            setBackground(LINE_NUMBER_BG);
            setForeground(LINE_NUMBER_FG);
            setOpaque(true);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(getFont());
            Element root = codeEditor.getDocument().getDefaultRootElement();
            int lineCount = Math.max(root.getElementCount(), 1);
            int digitWidth = metrics.stringWidth(String.valueOf(lineCount));
            int width = digitWidth + LEFT_PADDING + RIGHT_PADDING;
            // Height mirrors the editor's own preferred height so the
            // JScrollPane keeps this row header's scroll range in sync
            // with the main viewport automatically.
            int height = codeEditor.getPreferredSize().height;
            return new Dimension(width, height);
        }

        @Override
        @SuppressWarnings("deprecation") // modelToView(int) remains functional
                                          // across current JDKs; used here for
                                          // broad compatibility over modelToView2D.
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());

            g.setColor(getForeground());
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();

            Rectangle clip = g.getClipBounds();
            Element root = codeEditor.getDocument().getDefaultRootElement();
            int lineCount = root.getElementCount();

            for (int line = 0; line < lineCount; line++) {
                Element element = root.getElement(line);
                int startOffset = element.getStartOffset();
                try {
                    Rectangle lineView = codeEditor.modelToView(startOffset);
                    if (lineView == null) {
                        continue;
                    }
                    if (lineView.y + lineView.height < clip.y || lineView.y > clip.y + clip.height) {
                        continue; // Outside the currently painted region — skip.
                    }
                    String lineNumber = String.valueOf(line + 1);
                    int stringWidth = metrics.stringWidth(lineNumber);
                    int x = getWidth() - stringWidth - RIGHT_PADDING;
                    int y = lineView.y + metrics.getAscent();
                    g.drawString(lineNumber, x, y);
                } catch (BadLocationException e) {
                    // A line's start offset briefly falling outside the
                    // document's current bounds mid-edit is expected and
                    // harmless — that single line is skipped this repaint.
                }
            }
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    /**
     * Standalone launcher for manual UI testing. Applies the dark theme
     * BEFORE any Swing component is constructed (UIManager defaults are
     * read at construction time), then builds the existing backend and
     * starts it, exactly as SystemController already requires.
     */
    public static void main(String[] args) {
        installGlobalDarkTheme();

        SwingUtilities.invokeLater(() -> {
            SystemController controller = new SystemController();
            controller.start();

            SmartEvaluatorUI ui = new SmartEvaluatorUI(controller);
            ui.setVisible(true);
        });
    }

    private static void installGlobalDarkTheme() {
        UIManager.put("Panel.background", PANEL_BG);
        UIManager.put("SplitPane.background", PANEL_BG);
        UIManager.put("TabbedPane.background", PANEL_BG);
        UIManager.put("TabbedPane.foreground", TEXT_COLOR);
        UIManager.put("TabbedPane.selected", EDITOR_BG);
        UIManager.put("TabbedPane.contentAreaColor", EDITOR_BG);
        UIManager.put("Label.foreground", TEXT_COLOR);
        UIManager.put("ScrollBar.background", PANEL_BG);
        UIManager.put("ScrollBar.track", PANEL_BG);
        UIManager.put("ScrollBar.thumb", new Color(0x5E6060));
        UIManager.put("Viewport.background", EDITOR_BG);
        UIManager.put("TextArea.background", EDITOR_BG);
        UIManager.put("TextArea.foreground", TEXT_COLOR);
        UIManager.put("TextArea.caretForeground", TEXT_COLOR);
        UIManager.put("TextPane.background", EDITOR_BG);
        UIManager.put("TextPane.foreground", TEXT_COLOR);
        UIManager.put("TextPane.caretForeground", TEXT_COLOR);
    }
}