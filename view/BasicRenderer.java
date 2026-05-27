package view;

import java.awt.*;

public class BasicRenderer implements ViewRenderer {

    @Override
    public void renderAll(Graphics2D g2d) {
        // Bật khử răng cưa cho nét vẽ mượt hơn
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // --- CODE TEST VẼ TẠM THỜI ---
        // Xóa nền đen
        g2d.setColor(new Color(30, 30, 30));
        g2d.fillRect(0, 0, 2000, 2000);

        // Vẽ thử một con đường (Màu xám)
        g2d.setColor(Color.GRAY);
        g2d.fillRect(100, 300, 800, 100); // x, y, width, height

        // Vẽ thử một cái xe (Hình chữ nhật màu xanh)
        g2d.setColor(Color.CYAN);
        g2d.fillRect(150, 320, 60, 30);
        
        // Vẽ thử một đèn giao thông (Hình tròn đỏ)
        g2d.setColor(Color.RED);
        g2d.fillOval(450, 250, 30, 30); // x, y, width, height
    }

    // Các hàm drawVehicle, drawRoad... sau này bạn sẽ code chi tiết ở đây
    // @Override
    // public void drawVehicle(Graphics2D g2d, Vehicle v) { ... }
    @Override
public void drawVehicle(Graphics2D g2d, Vehicle v) {
    // Lấy tọa độ thật từ đối tượng Vehicle do team logic tính toán
    int x = (int) v.getPositionX(); // Tên hàm get tùy team bạn đặt
    int y = (int) v.getPositionY();
    
    // Tùy biến màu sắc hoặc kích thước dựa trên loại xe
    g2d.setColor(Color.CYAN);
    g2d.fillRect(x, y, 60, 30); 
}

@Override
public void drawTrafficLight(Graphics2D g2d, TrafficLight tl) {
    // Lấy tọa độ và trạng thái đèn
    int x = tl.getX();
    int y = tl.getY();
    String color = tl.getColor(); // Ví dụ: "RED", "GREEN"
    
    if (color.equals("RED")) g2d.setColor(Color.RED);
    else if (color.equals("GREEN")) g2d.setColor(Color.GREEN);
    else g2d.setColor(Color.YELLOW);
    
    g2d.fillOval(x, y, 20, 20);
}
}