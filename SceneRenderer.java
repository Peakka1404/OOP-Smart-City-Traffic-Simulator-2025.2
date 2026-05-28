import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * SceneRenderer — Vẽ toàn cảnh.
 * Hỗ trợ load nhiều loại xe từ thư mục resources
 */
public class SceneRenderer {

    public enum VehicleMode { BASIC, GRAPHIC, IMAGE }
    private VehicleMode vehicleMode = VehicleMode.IMAGE; // Mặc định sử dụng ảnh xe thật
    private boolean showHitbox = false;
    private boolean showPath   = false;
    private boolean showNodes  = true;

    // ── Palette ───────────────────────────────────────────────────────────
    private static final Color C_BG       = new Color(28, 34, 28);
    private static final Color C_SIDEWALK = new Color(108, 103, 94);
    private static final Color C_ROAD     = new Color(55, 58, 64);
    private static final Color C_BARRIER  = new Color(235, 235, 235);
    private static final Color C_CENTER   = new Color(240, 240, 240);
    private static final Color C_LANEDASH = new Color(225, 225, 225, 190);
    private static final Color C_NODE_F   = new Color(68, 76, 90);
    private static final Color C_NODE_R   = new Color(115, 155, 205);

    private static final Color C_MOVING   = new Color( 52, 152, 219);
    private static final Color C_SLOWING  = new Color(230, 126,  34);
    private static final Color C_STOPPED  = new Color(192,  57,  43);
    private static final Color C_YIELD    = new Color(241, 196,  15);
    private static final Color C_OVERTAKE = new Color(155,  89, 182);
    private static final Color C_WAITING  = new Color(231,  76,  60);

    // ── Nơi lưu trữ NHIỀU ảnh xe ──────────────────────────────────────────
    // Dùng Map để ánh xạ Tên xe -> Ảnh tương ứng
    // Scale riêng cho từng loại xe — chỉnh số này tùy ý
    private static final Map<String, Double> VEHICLE_SCALE = new HashMap<>() {{
        put("Car",       1.8);
        put("Ambulance",  2.0);
        put("FireTruck", 2.0);
        put("Motorbike", 1.6);
        put("Bicycle",   1.3);
    }};
    // Hệ số thu nhỏ vùng bóng so với ảnh (1.0 = bằng ảnh, 0.8 = nhỏ hơn 20%)
private static final Map<String, Double> VEHICLE_SHADOW_FIT = new HashMap<>() {{
    put("Car",       1.0);
    put("Ambulance", 0.75);  // ảnh có nhiều vùng trong suốt → thu bóng lại
    put("FireTruck", 1.0);
    put("Motorbike", 1.0);
    put("Bicycle",   1.0);
}};
    private Map<String, BufferedImage> vehicleSprites = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────
    //  Constructor & Tải tài nguyên
    // ─────────────────────────────────────────────────────────────────────
    
    public SceneRenderer() {
        loadResources();
    }

    /**
     * Tải tất cả file ảnh xe vào bộ nhớ
     */
    private void loadResources() {
        // Danh sách tên các file ảnh bạn có (không cần đuôi .png)
        // Chữ cái đầu viết hoa cho khớp với tên file của bạn trong VS Code
        String[] vehicleTypes = {"Ambulance", "Bicycle", "Car", "FireTruck", "Motorbike"};
        String basePath = "resources/"; 

        for (String type : vehicleTypes) {
            File imgFile = new File(basePath + type + ".png");
            try {
                if (imgFile.exists()) {
                    vehicleSprites.put(type, ImageIO.read(imgFile));
                    System.out.println("[SceneRenderer] Tải ảnh thành công: " + type + ".png");
                } else {
                    System.err.println("[SceneRenderer] Thiếu file ảnh: " + imgFile.getAbsolutePath());
                }
            } catch (IOException e) {
                System.err.println("[SceneRenderer] Lỗi đọc ảnh " + type + ": " + e.getMessage());
            }
        }

        // Nếu không tải được ảnh nào thì fallback về vẽ khối
        if (vehicleSprites.isEmpty()) {
            System.err.println("[SceneRenderer] Không tải được ảnh nào. Chuyển về GRAPHIC Mode.");
            vehicleMode = VehicleMode.GRAPHIC;
        }
    }

    /**
     * Hàm phụ trợ giúp tìm đúng ảnh cho chiếc xe đang cần vẽ
     */
    private BufferedImage getSpriteForVehicle(Vehicle v) {
        // CÁCH 1: Giả sử các xe của bạn là các class kế thừa Vehicle (ví dụ: class Car extends Vehicle)
        String typeName = v.getType();
        
        // CÁCH 2: Nếu bạn dùng một trường (field) để phân biệt loại xe trong class Vehicle, 
        // ví dụ: v.getType() trả về "Car", "Bicycle"... thì hãy bỏ comment dòng dưới và xóa dòng trên:
        // String typeName = v.getType();

        // Kiểm tra xem trong Map có ảnh của loại xe này không
        if (vehicleSprites.containsKey(typeName)) {
            return vehicleSprites.get(typeName);
        }
        
        // Nếu AI sinh ra một loại xe lạ chưa có ảnh, mặc định lấy ảnh "Car"
        return vehicleSprites.get("Car"); 
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Entry - Hàm Render chính
    // ─────────────────────────────────────────────────────────────────────

    public void render(Graphics2D g2, RoadNetwork network) {
        aa(g2);

        for (Road r : network.getRoads()) drawSidewalk(g2, r);
        for (Road r : network.getRoads()) drawRoadSurface(g2, r);
        drawAllMarkings(g2, network.getRoads());
        for (Road r : network.getRoads()) drawBarriers(g2, r);
        for (SpawnZone sz : network.getSpawnZones()) sz.render(g2);
        if (showNodes) for (Node n : network.getNodes()) drawNode(g2, n);
        for (IntersectionController ic : network.getAllIntersectionControllers())
            drawTrafficLights(g2, ic);

        // Xe
        for (Vehicle v : network.getVehicles()) {
            if (showPath)   drawPath(g2, v);
            
            switch (vehicleMode) {
                case IMAGE   -> drawVehicleImage(g2, v);
                case GRAPHIC -> drawVehicleGraphic(g2, v);
                case BASIC   -> drawVehicleBasic(g2, v);
            }
            
            if (showHitbox) drawHitbox(g2, v);
        }
    }

    // ... [Các hàm drawSidewalk, drawRoadSurface, drawAllMarkings, drawBarriers, roadPoly, drawTrafficLights, drawOneLight, drawNode giữ nguyên giống y hệt code trước] ...
    private void drawSidewalk(Graphics2D g, Road r) { g.setColor(C_SIDEWALK); g.fill(roadPoly(r, r.getHalfWidth() + 6)); }
    private void drawRoadSurface(Graphics2D g, Road r) { g.setColor(C_ROAD); g.fill(roadPoly(r, r.getHalfWidth())); }
    private void drawAllMarkings(Graphics2D g, List<Road> roads) { Set<String> centerDone = new HashSet<>(); for (Road r : roads) { double hw = r.getHalfWidth(); int lc = r.getLaneCount(); double len = r.getLength(); double margin = 12; String key = sortedKey(r.getFrom().getId(), r.getTo().getId()); if (!centerDone.contains(key)) { boolean hasTwin = roads.stream().anyMatch(t -> t.getFrom()==r.getTo() && t.getTo()==r.getFrom()); if (hasTwin) { g.setColor(C_CENTER); g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)); double[] a = r.localToWorld(margin, 0); double[] b = r.localToWorld(len - margin, 0); g.draw(new Line2D.Double(a[0],a[1],b[0],b[1])); g.setStroke(new BasicStroke(1)); centerDone.add(key); } } if (lc < 2) continue; double laneSpacing = hw / lc; g.setColor(C_LANEDASH); g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{14, 11}, 0)); for (int i = 1; i < lc; i++) { double s = i * laneSpacing; double[] p1 = r.localToWorld(margin, s); double[] p2 = r.localToWorld(len - margin, s); g.draw(new Line2D.Double(p1[0],p1[1],p2[0],p2[1])); } g.setStroke(new BasicStroke(1)); } }
    private String sortedKey(String a, String b) { return a.compareTo(b) < 0 ? a+"|"+b : b+"|"+a; }
    private void drawBarriers(Graphics2D g, Road r) { double hw = r.getHalfWidth(); double[] a1=r.localToWorld(0,-hw), a2=r.localToWorld(r.getLength(),-hw); double[] b1=r.localToWorld(0, hw), b2=r.localToWorld(r.getLength(), hw); g.setColor(C_BARRIER); g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(new Line2D.Double(a1[0],a1[1],a2[0],a2[1])); g.draw(new Line2D.Double(b1[0],b1[1],b2[0],b2[1])); g.setStroke(new BasicStroke(1)); }
    private Path2D roadPoly(Road r, double hw) { double len = r.getLength(); double[][] c = { r.localToWorld(0,-hw), r.localToWorld(len,-hw), r.localToWorld(len, hw), r.localToWorld(0, hw) }; Path2D p = new Path2D.Double(); p.moveTo(c[0][0],c[0][1]); for (int i=1;i<4;i++) p.lineTo(c[i][0],c[i][1]); p.closePath(); return p; }
    private void drawTrafficLights(Graphics2D g, IntersectionController ic) { for (Map.Entry<Road, TrafficLight> e : ic.getLights().entrySet()) { Road road = e.getKey(); TrafficLight tl = e.getValue(); double hw = road.getHalfWidth(); double stopT = road.getLength() - hw; if (stopT < 0) continue; double lightS = hw + 18; double[] pos = road.localToWorld(stopT, lightS); tl.setRenderPos(pos[0], pos[1]); drawOneLight(g, tl); } }
    private void drawOneLight(Graphics2D g, TrafficLight tl) { int bw=13, bh=32; double rx=tl.getRenderX(), ry=tl.getRenderY(); g.setColor(new Color(25,25,25)); g.fillRoundRect((int)(rx-bw/2),(int)(ry-bh/2), bw, bh, 4, 4); g.setColor(new Color(55,55,55)); g.setStroke(new BasicStroke(0.8f)); g.drawRoundRect((int)(rx-bw/2),(int)(ry-bh/2), bw, bh, 4, 4); g.setStroke(new BasicStroke(1)); int r=4; int[] yy={(int)(ry-bh/2+7),(int)(ry),(int)(ry+bh/2-7)}; Color[] base={Color.DARK_GRAY, Color.DARK_GRAY, Color.DARK_GRAY}; switch(tl.getState()){ case RED -> base[0]=new Color(255,55,55); case YELLOW -> base[1]=new Color(255,200,0); case GREEN -> base[2]=new Color(0,215,80); } for(int i=0;i<3;i++){ if(!base[i].equals(Color.DARK_GRAY)){ g.setColor(new Color(base[i].getRed(),base[i].getGreen(),base[i].getBlue(),55)); g.fillOval((int)(rx-r*2),(int)(yy[i]-r*2),r*4,r*4); } g.setColor(base[i]); g.fillOval((int)(rx-r),(int)(yy[i]-r),r*2,r*2); } double left=tl.getTimeLeft(); if(left>0.09){ g.setFont(new Font("Monospaced",Font.BOLD,8)); g.setColor(Color.WHITE); String s=String.format("%.0f",Math.ceil(left)); FontMetrics fm=g.getFontMetrics(); g.drawString(s,(float)(rx-fm.stringWidth(s)/2.0),(float)(ry+bh/2+9)); } g.setColor(new Color(75,75,75)); g.fillRect((int)(rx-1),(int)(ry+bh/2),2,11); }
    private void drawNode(Graphics2D g, Node n) { double r=Node.ARRIVAL_RADIUS,x=n.getX(),y=n.getY(); g.setColor(C_NODE_F); g.fill(new Ellipse2D.Double(x-r,y-r,r*2,r*2)); g.setColor(C_NODE_R); g.setStroke(new BasicStroke(1.5f)); g.draw(new Ellipse2D.Double(x-r,y-r,r*2,r*2)); g.setStroke(new BasicStroke(1)); g.setColor(new Color(165,195,235)); g.setFont(new Font("Monospaced",Font.BOLD,8)); FontMetrics fm=g.getFontMetrics(); String lbl=n.getId(); g.drawString(lbl,(float)(x-fm.stringWidth(lbl)/2.0),(float)(y-r-3)); }

    // ─────────────────────────────────────────────────────────────────────
    //  Vehicle: CHẾ ĐỘ VẼ ẢNH THẬT
    // ─────────────────────────────────────────────────────────────────────

private void drawVehicleImage(Graphics2D g, Vehicle v) {
    BufferedImage sprite = getSpriteForVehicle(v);
    if (sprite == null) { drawVehicleGraphic(g, v); return; }

    Graphics2D g2 = (Graphics2D) g.create();
    g2.translate(v.getX(), v.getY());
    g2.rotate(v.getAngle());

    // Ảnh quay ngang → chiều dài xe (Height) = trục X, chiều rộng (Width) = trục Y
double scale = VEHICLE_SCALE.getOrDefault(v.getType(), 1.6);
double halfLen = v.getHitboxHeight() / 2 * scale;
double halfWid = v.getHitboxWidth()  / 2 * scale;

    // Bóng đổ — cùng kích thước ảnh, lệch nhẹ
    g2.setColor(new Color(0, 0, 0, 65));
    double shadowFit = VEHICLE_SHADOW_FIT.getOrDefault(v.getType(), 1.0);
    double sLen = halfLen * shadowFit;
    double sWid = halfWid * shadowFit;
    g2.fill(new RoundRectangle2D.Double(
        -sLen + sLen * 0.03,
        -sWid + sWid * 0.03,
        sLen * 2,
        sWid * 2, 5, 5));

    // Vẽ ảnh
    g2.drawImage(sprite,
        (int)(-halfLen), (int)(-halfWid),
        (int)(halfLen * 2), (int)(halfWid * 2), null);

    drawStateOverlaysRightFacing(g2, v, halfLen, halfWid);
    g2.dispose();
}

private void drawStateOverlaysRightFacing(Graphics2D g2, Vehicle v, double halfLen, double halfWid) {
    boolean braking = v.getState() == Vehicle.State.SLOWING
                   || v.getState() == Vehicle.State.STOPPED
                   || v.getState() == Vehicle.State.WAITING_LIGHT;

    // Đèn hậu — nằm ở đuôi xe (x = -halfLen), 2 chấm trên/dưới
    if (braking) g2.setColor(new Color(255, 30, 30, 230));
    else         g2.setColor(new Color(160, 10, 10, 150));
    double dotSize = halfWid * 0.4;
    g2.fill(new Ellipse2D.Double(-halfLen + 1, -halfWid * 0.6, dotSize, dotSize));
    g2.fill(new Ellipse2D.Double(-halfLen + 1,  halfWid * 0.2, dotSize, dotSize));

    if (v.isOvertaking()) {
        g2.setColor(new Color(155, 89, 182, 80));
        g2.fill(new RoundRectangle2D.Double(
            halfLen * 0.5, -halfWid, halfLen * 0.5, halfWid * 2, 3, 3));
    }
    if (v.getState() == Vehicle.State.YIELDING) {
        g2.setColor(new Color(241, 196, 15, 80));
        g2.fill(new RoundRectangle2D.Double(
            halfLen * 0.5, -halfWid, halfLen * 0.5, halfWid * 2, 3, 3));
    }
}
    // ─────────────────────────────────────────────────────────────────────
    //  Vehicle: CÁC HÀM VẼ KHỐI (DỰ PHÒNG)
    // ─────────────────────────────────────────────────────────────────────

    private void drawVehicleBasic(Graphics2D g, Vehicle v) {
        Graphics2D g2=(Graphics2D)g.create();
        g2.translate(v.getX(),v.getY()); g2.rotate(v.getAngle()+Math.PI/2);
        double hw=v.getHitboxWidth()/2, hh=v.getHitboxHeight()/2;
        g2.setColor(new Color(0,0,0,50));
        g2.fill(new RoundRectangle2D.Double(-hw+2,-hh+2,hw*2,hh*2,4,4));
        Color bc=vColor(v); g2.setColor(bc);
        g2.fill(new RoundRectangle2D.Double(-hw,-hh,hw*2,hh*2,4,4));
        g2.setColor(bc.darker().darker()); g2.setStroke(new BasicStroke(0.8f));
        g2.draw(new RoundRectangle2D.Double(-hw,-hh,hw*2,hh*2,4,4));
        g2.rotate(-(v.getAngle()+Math.PI/2));
        g2.setFont(new Font("Monospaced",Font.BOLD,7)); g2.setColor(Color.WHITE);
        FontMetrics fm=g2.getFontMetrics(); String lbl=v.getId();
        g2.drawString(lbl,(float)(-fm.stringWidth(lbl)/2.0),(float)(fm.getAscent()/2.0-1));
        g2.dispose();
    }

    private void drawVehicleGraphic(Graphics2D g, Vehicle v) {
        Graphics2D g2=(Graphics2D)g.create();
        g2.translate(v.getX(),v.getY()); g2.rotate(v.getAngle()+Math.PI/2);
        double hw=v.getHitboxWidth()/2, hh=v.getHitboxHeight()/2;
        Color bc=vColor(v);

        g2.setColor(new Color(0,0,0,55));
        g2.fill(new RoundRectangle2D.Double(-hw+2,-hh+2,hw*2,hh*2,5,5));
        g2.setColor(bc);
        g2.fill(new RoundRectangle2D.Double(-hw,-hh,hw*2,hh*2,5,5));

        double rw=hw*0.65,rh=hh*0.5;
        g2.setColor(bc.darker());
        g2.fill(new RoundRectangle2D.Double(-rw,-rh*0.4,rw*2,rh*1.6,3,3));

        g2.setColor(new Color(180,230,255,190));
        g2.fill(new RoundRectangle2D.Double(-rw+1,-hh+2,rw*2-2,hh*0.35,2,2));
        g2.fill(new RoundRectangle2D.Double(-rw+1,hh*0.55,rw*2-2,hh*0.25,2,2));

        g2.setColor(new Color(255,255,200,220));
        g2.fill(new Ellipse2D.Double(-hw+1,-hh+1,hw*0.7,3));
        g2.fill(new Ellipse2D.Double(hw*0.3,-hh+1,hw*0.7,3));

        boolean braking=v.getState()==Vehicle.State.SLOWING ||v.getState()==Vehicle.State.STOPPED ||v.getState()==Vehicle.State.WAITING_LIGHT;
        g2.setColor(braking?new Color(255,60,60,230):new Color(155,30,30,180));
        g2.fill(new Ellipse2D.Double(-hw+1,hh-4,hw*0.7,3));
        g2.fill(new Ellipse2D.Double(hw*0.3,hh-4,hw*0.7,3));

        if(v.isOvertaking()){g2.setColor(new Color(155,89,182,100));g2.fill(new RoundRectangle2D.Double(-rw,-rh*0.4,rw*2,rh*0.5,2,2));}
        if(v.getState()==Vehicle.State.YIELDING){g2.setColor(new Color(241,196,15,100));g2.fill(new RoundRectangle2D.Double(-rw,-rh*0.4,rw*2,rh*0.5,2,2));}

        g2.setColor(bc.darker().darker()); g2.setStroke(new BasicStroke(0.8f));
        g2.draw(new RoundRectangle2D.Double(-hw,-hh,hw*2,hh*2,5,5));
        g2.setStroke(new BasicStroke(1));
        g2.dispose();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Hitbox + Path + Helpers [GIỮ NGUYÊN]
    // ─────────────────────────────────────────────────────────────────────

    private void drawHitbox(Graphics2D g, Vehicle v) { double[][]c=v.getHitboxCorners(); Path2D p=new Path2D.Double(); p.moveTo(c[0][0],c[0][1]); for(int i=1;i<4;i++) p.lineTo(c[i][0],c[i][1]); p.closePath(); double r=v.getHitboxRadius(); g.setColor(new Color(255,80,80,45)); g.fill(new Ellipse2D.Double(v.getX()-r,v.getY()-r,r*2,r*2)); g.setColor(new Color(255,200,0)); g.setStroke(new BasicStroke(1f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,1,new float[]{4,3},0)); g.draw(p); g.setStroke(new BasicStroke(1)); }
    private void drawPath(Graphics2D g, Vehicle v) { List<Node> path=v.getPath(); if(path.size()<2) return; g.setColor(new Color(100,200,255,110)); g.setStroke(new BasicStroke(1.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{6,5},0)); for(int i=v.getPathIndex();i<path.size()-1;i++){ Node a=path.get(i),b=path.get(i+1); g.draw(new Line2D.Double(a.getX(),a.getY(),b.getX(),b.getY())); } Node dest=v.getDestination(); g.setColor(new Color(255,100,100,200)); g.fill(new Ellipse2D.Double(dest.getX()-5,dest.getY()-5,10,10)); g.setStroke(new BasicStroke(1)); }

    private Color vColor(Vehicle v) { return switch(v.getState()){ case WAITING_LIGHT -> C_WAITING; case YIELDING -> C_YIELD; case STOPPED -> C_STOPPED; case SLOWING -> C_SLOWING; case ARRIVED -> new Color(39,174,96); default -> v.isOvertaking() ? C_OVERTAKE : C_MOVING; }; }
    private void aa(Graphics2D g){ g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY); }

    public void setVehicleMode(VehicleMode m){ vehicleMode=m; }
    public void setShowHitbox(boolean b)     { showHitbox=b; }
    public void setShowPath(boolean b)       { showPath=b; }
    public void setShowNodes(boolean b)      { showNodes=b; }
    public VehicleMode getVehicleMode()      { return vehicleMode; }
    public boolean isShowHitbox()            { return showHitbox; }
    public boolean isShowPath()              { return showPath; }
}