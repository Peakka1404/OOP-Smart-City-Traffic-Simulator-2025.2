import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Main — Điểm khởi động. Hiện dialog chọn chế độ.
 */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}

            int choice = showStartupDialog();
            if (choice < 0) System.exit(0);   // user closed dialog

            boolean moduleMode = (choice == 1);
            new MainWindow(moduleMode).setVisible(true);
        });
    }

    /** Returns 0 = Normal, 1 = Module, -1 = cancelled. */
    private static int showStartupDialog() {
        JDialog dlg = new JDialog((Frame) null, "Traffic Simulation", true);
        dlg.setLayout(new BorderLayout());
        dlg.setResizable(false);

        // ── Background panel ──────────────────────────────────────────────
        JPanel bg = new JPanel(new BorderLayout(0, 20));
        bg.setBackground(new Color(22, 26, 32));
        bg.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));

        // Title
        JLabel title = new JLabel("🚗  Traffic Simulation", JLabel.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setForeground(new Color(80, 160, 240));
        bg.add(title, BorderLayout.NORTH);

        // Sub-title
        JLabel sub = new JLabel("Chọn chế độ hoạt động", JLabel.CENTER);
        sub.setFont(new Font("SansSerif", Font.PLAIN, 13));
        sub.setForeground(new Color(140, 150, 165));

        // Buttons
        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 16, 0));
        btnPanel.setBackground(new Color(22, 26, 32));

        JButton normalBtn = makeModeButton(
            "▶  Chế Độ Thường",
            "Mô phỏng giao thông\ntrên mạng lưới mặc định",
            new Color(52, 152, 219));

        JButton moduleBtn = makeModeButton(
            "🔧  Chế Độ Module",
            "Xây dựng mạng lưới\nbằng kéo-thả",
            new Color(39, 174, 96));

        int[] result = {-1};
        normalBtn.addActionListener(e -> { result[0] = 0; dlg.dispose(); });
        moduleBtn.addActionListener(e -> { result[0] = 1; dlg.dispose(); });

        btnPanel.add(normalBtn); btnPanel.add(moduleBtn);

        JPanel center = new JPanel(new BorderLayout(0, 14));
        center.setBackground(new Color(22, 26, 32));
        center.add(sub, BorderLayout.NORTH);
        center.add(btnPanel, BorderLayout.CENTER);
        bg.add(center, BorderLayout.CENTER);

        // Footer
        JLabel footer = new JLabel("Java " + System.getProperty("java.version"), JLabel.CENTER);
        footer.setFont(new Font("Monospaced", Font.PLAIN, 10));
        footer.setForeground(new Color(60, 65, 75));
        bg.add(footer, BorderLayout.SOUTH);

        dlg.add(bg);
        dlg.pack();
        dlg.setLocationRelativeTo(null);
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dlg.dispose(); }
        });
        dlg.setVisible(true);
        return result[0];
    }

    private static JButton makeModeButton(String title, String desc, Color accent) {
        JButton btn = new JButton() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = getModel().isPressed()   ? accent.darker() :
                           getModel().isRollover()  ? accent           :
                                                      new Color(35, 40, 48);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(accent);
                g2.setStroke(new BasicStroke(1.8f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };

        btn.setLayout(new BorderLayout(0, 6));
        btn.setOpaque(false); btn.setContentAreaFilled(false); btn.setBorderPainted(false);
        btn.setFocusPainted(false); btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(190, 110));

        JLabel tl = new JLabel("<html><center>" + title + "</center></html>", JLabel.CENTER);
        tl.setFont(new Font("SansSerif", Font.BOLD, 13));
        tl.setForeground(Color.WHITE);

        JLabel dl = new JLabel("<html><center>" + desc.replace("\n","<br>") + "</center></html>", JLabel.CENTER);
        dl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        dl.setForeground(new Color(180, 185, 195));

        JPanel inner = new JPanel(new BorderLayout(0, 5));
        inner.setOpaque(false);
        inner.setBorder(BorderFactory.createEmptyBorder(14, 10, 14, 10));
        inner.add(tl, BorderLayout.NORTH);
        inner.add(dl, BorderLayout.CENTER);
        btn.add(inner);
        return btn;
    }
}
