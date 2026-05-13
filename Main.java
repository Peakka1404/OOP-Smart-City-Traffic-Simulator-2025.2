import javax.swing.*;

/**
 * Main — Điểm vào của ứng dụng Traffic Simulation.
 *
 * Yêu cầu: Java 17+ (dùng switch expression).
 * Chạy: java Main
 */
public class Main {
    public static void main(String[] args) {
        // Chạy trên Event Dispatch Thread (EDT) theo chuẩn Swing
        SwingUtilities.invokeLater(() -> {
            // Thử dùng system look-and-feel (giữ màu custom của chúng ta)
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            new MainWindow().setVisible(true);
        });
    }
}
