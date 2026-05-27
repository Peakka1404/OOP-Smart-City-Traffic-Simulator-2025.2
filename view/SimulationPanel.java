package view;

import java.awt.*;
import javax.swing.*;

public class SimulationPanel extends JPanel {
    
    private ViewRenderer renderer;

    public SimulationPanel() {
        // Mặc định sử dụng BasicRenderer
        this.renderer = new BasicRenderer();
        
        // Bạn có thể đổi sang GraphicRenderer sau khi có ảnh
        // this.renderer = new GraphicRenderer();
    }

    // Swing tự động gọi hàm này mỗi lần cần cập nhật màn hình (mỗi frame)
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;

        // Gọi Renderer để vẽ mọi thứ ra bảng này
        if (renderer != null) {
            renderer.renderAll(g2d);
        }
    }

    // Hàm này để thay đổi cách vẽ (từ Basic sang Graphic nếu muốn)
    public void setRenderer(ViewRenderer renderer) {
        this.renderer = renderer;
    }
}