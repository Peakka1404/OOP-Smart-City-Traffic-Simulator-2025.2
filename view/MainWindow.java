package view;

import javax.swing.*;
import java.awt.*;

public class MainWindow extends JFrame {
    
    private SimulationPanel simulationPanel;

    public MainWindow(boolean moduleMode) {
        // Cài đặt thông số cơ bản cho cửa sổ
        setTitle("Mô Phỏng Giao Thông - " + (moduleMode ? "Chế độ Module" : "Chế độ Thường"));
        setSize(1024, 768); 
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null); // Hiển thị ở giữa màn hình
        setLayout(new BorderLayout());

        // Khởi tạo bảng vẽ và thêm vào cửa sổ
        simulationPanel = new SimulationPanel();
        add(simulationPanel, BorderLayout.CENTER);

        // TODO: Chỗ này sau này sẽ kết nối với TrafficController của team logic
        // Ví dụ: TrafficController controller = new TrafficController(simulationPanel, moduleMode);
        // controller.start();
    }
}
