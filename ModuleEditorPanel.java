import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * ModuleEditorPanel v8:
 *  NEW TOOLS: 45° road, 135° road, Flex Intersection
 *  FIX: can now connect two facing roads (extendFromTerminal snaps to existing node)
 *  Invalid placements show RED preview + error message
 *  Available direction arrows at each terminal
 */
public class ModuleEditorPanel extends JPanel {

    public enum Tool { NONE, H_ROAD, V_ROAD, ROAD_45, ROAD_135, INTERSECTION, FLEX_INTERSECTION }
    private Tool currentTool = Tool.NONE;

    private final RoadNetwork  network;
    private final SceneRenderer renderer = new SceneRenderer();

    // Camera
    private double camX=0,camY=0,camZoom=1.0;
    private static final double ZOOM_MIN=0.08,ZOOM_MAX=6.0,ZOOM_STEP=0.11;
    private Point  dragStart; private double cxD,cyD;

    // Editor state
    private int    mouseX,mouseY;
    private SpawnZone snapTarget=null;
    private double previewAngle=0;
    private boolean previewValid=false;
    private double previewLength=0;
    private double blinkTimer=0; private boolean blinkState=false;
    private int    editCount=0;
    private String errorMsg=null; private long errorExpiry=0;

    private BufferedImage buffer;
    private JButton btnH,btnV,btn45,btn135,btnI,btnFlex;
    private Runnable onSimulate;

    public ModuleEditorPanel(RoadNetwork network, Runnable onSimulate) {
        this.network=network; this.onSimulate=onSimulate;
        setBackground(new Color(22,26,30)); setFocusable(true);
        renderer.setShowNodes(true);
        setupInput();
        new javax.swing.Timer(50,e->{blinkTimer+=0.05;blinkState=(((int)(blinkTimer*2))%2==0);repaint();}).start();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Toolbar
    // ─────────────────────────────────────────────────────────────────────

    public JPanel buildToolbar() {
        JPanel tb=new JPanel(new FlowLayout(FlowLayout.LEFT,6,6));
        tb.setBackground(new Color(28,32,40));
        tb.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(50,55,65)));

        JLabel lbl=new JLabel("  🔧  Module   |");
        lbl.setFont(new Font("SansSerif",Font.BOLD,13)); lbl.setForeground(new Color(80,160,240)); tb.add(lbl);

        btnH   =makeToolBtn("━  Ngang",    "[1] Đường ngang",           Tool.H_ROAD);
        btnV   =makeToolBtn("┃  Dọc",      "[2] Đường dọc",             Tool.V_ROAD);
        btn45  =makeToolBtn("╱  45°",      "[3] Đường 45° (chéo ↗)",    Tool.ROAD_45);
        btn135 =makeToolBtn("╲  135°",     "[4] Đường 135° (chéo ↘)",   Tool.ROAD_135);
        btnI   =makeToolBtn("✚  Ngã Tư",   "[5] Ngã tư đầy đủ (4 nhánh)",Tool.INTERSECTION);
        btnFlex=makeToolBtn("⊕  Flex",     "[6] Ngã tư linh hoạt (mọi hướng)",Tool.FLEX_INTERSECTION);
        tb.add(btnH);tb.add(btnV);tb.add(btn45);tb.add(btn135);tb.add(btnI);tb.add(btnFlex);

        tb.add(makeSep());
        JButton btnUndo=makeActionBtn("↩  Undo",new Color(52,73,94));
        btnUndo.setToolTipText("Ctrl+Z");
        btnUndo.addActionListener(e->doUndo()); tb.add(btnUndo);
        tb.add(makeSep());
        JButton btnSim=makeActionBtn("▶  Mô Phỏng",new Color(39,174,96));
        btnSim.addActionListener(e->{if(onSimulate!=null)onSimulate.run();}); tb.add(btnSim);

        JLabel hint=new JLabel("  [Click tool → Click vùng xanh]  Scroll=Zoom  Drag-P=Pan  ESC=Bỏ chọn  ⊕=kéo các terminal lại gần nhau");
        hint.setFont(new Font("SansSerif",Font.ITALIC,10)); hint.setForeground(new Color(90,100,115)); tb.add(hint);
        return tb;
    }

    private JSeparator makeSep(){JSeparator s=new JSeparator(SwingConstants.VERTICAL);s.setPreferredSize(new Dimension(1,28));s.setForeground(new Color(55,60,70));return s;}
    private JButton makeToolBtn(String t,String tip,Tool tool){JButton b=makeActionBtn(t,new Color(45,50,62));b.setToolTipText(tip);b.addActionListener(e->selectTool(tool));return b;}
    private JButton makeActionBtn(String t,Color bg){JButton b=new JButton(t);b.setBackground(bg);b.setForeground(Color.WHITE);b.setFont(new Font("SansSerif",Font.BOLD,11));b.setFocusPainted(false);b.setBorderPainted(false);b.setOpaque(true);b.setBorder(BorderFactory.createEmptyBorder(5,8,5,8));b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return b;}

    private void selectTool(Tool tool){currentTool=(currentTool==tool)?Tool.NONE:tool;updateHighlights();snapTarget=null;previewValid=false;repaint();}

    private void updateHighlights(){
        Color def=new Color(45,50,62),sel=new Color(52,152,219);
        if(btnH!=null)   btnH.setBackground(currentTool==Tool.H_ROAD?sel:def);
        if(btnV!=null)   btnV.setBackground(currentTool==Tool.V_ROAD?sel:def);
        if(btn45!=null)  btn45.setBackground(currentTool==Tool.ROAD_45?sel:def);
        if(btn135!=null) btn135.setBackground(currentTool==Tool.ROAD_135?sel:def);
        if(btnI!=null)   btnI.setBackground(currentTool==Tool.INTERSECTION?sel:def);
        if(btnFlex!=null)btnFlex.setBackground(currentTool==Tool.FLEX_INTERSECTION?sel:def);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Input
    // ─────────────────────────────────────────────────────────────────────

    private void setupInput(){
        MouseAdapter ma=new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){
                requestFocusInWindow();
                if(e.getButton()==3||e.getButton()==2){dragStart=e.getPoint();cxD=camX;cyD=camY;}
            }
            @Override public void mouseReleased(MouseEvent e){if(e.getButton()==3||e.getButton()==2)dragStart=null;}
            @Override public void mouseDragged(MouseEvent e){
                if(dragStart!=null){camX=cxD-(e.getX()-dragStart.x)/camZoom;camY=cyD-(e.getY()-dragStart.y)/camZoom;repaint();}
            }
            @Override public void mouseClicked(MouseEvent e){
                if(e.getButton()==1){if(e.getClickCount()==2){fitCamera();return;}handleClick(e.getX(),e.getY());}
            }
            @Override public void mouseMoved(MouseEvent e){mouseX=e.getX();mouseY=e.getY();updatePreview();repaint();}
        };
        addMouseListener(ma);addMouseMotionListener(ma);
        addMouseWheelListener(e->{double f=e.getWheelRotation()<0?1+ZOOM_STEP:1-ZOOM_STEP;zoomAt(e.getX(),e.getY(),f);repaint();});
        addKeyListener(new KeyAdapter(){
            @Override public void keyPressed(KeyEvent e){
                switch(e.getKeyCode()){
                    case KeyEvent.VK_ESCAPE->{currentTool=Tool.NONE;updateHighlights();snapTarget=null;repaint();}
                    case KeyEvent.VK_F->fitCamera();
                    case KeyEvent.VK_LEFT,KeyEvent.VK_A->{camX-=30/camZoom;repaint();}
                    case KeyEvent.VK_RIGHT,KeyEvent.VK_D->{camX+=30/camZoom;repaint();}
                    case KeyEvent.VK_UP,KeyEvent.VK_W->{camY-=30/camZoom;repaint();}
                    case KeyEvent.VK_DOWN,KeyEvent.VK_S->{camY+=30/camZoom;repaint();}
                    case KeyEvent.VK_EQUALS,KeyEvent.VK_PLUS->zoomAt(getWidth()/2,getHeight()/2,1+ZOOM_STEP*2);
                    case KeyEvent.VK_MINUS->zoomAt(getWidth()/2,getHeight()/2,1-ZOOM_STEP*2);
                    case KeyEvent.VK_Z->{if((e.getModifiersEx()&InputEvent.CTRL_DOWN_MASK)!=0)doUndo();}
                    case KeyEvent.VK_1->selectTool(Tool.H_ROAD);
                    case KeyEvent.VK_2->selectTool(Tool.V_ROAD);
                    case KeyEvent.VK_3->selectTool(Tool.ROAD_45);
                    case KeyEvent.VK_4->selectTool(Tool.ROAD_135);
                    case KeyEvent.VK_5->selectTool(Tool.INTERSECTION);
                    case KeyEvent.VK_6->selectTool(Tool.FLEX_INTERSECTION);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Preview
    // ─────────────────────────────────────────────────────────────────────

    private void updatePreview(){
        if(currentTool==Tool.NONE||currentTool==Tool.FLEX_INTERSECTION){snapTarget=null;previewValid=false;repaint();return;}
        double[]w=s2w(mouseX,mouseY);
        snapTarget=findNearestSpawnZone(w[0],w[1],90.0/camZoom);
        if(snapTarget!=null) computePreview(snapTarget,w[0],w[1]);
        else previewValid=false;
    }

    private void computePreview(SpawnZone sz, double wx, double wy){
        Node node=sz.getNode();
        double nx=node.getX(),ny=node.getY();
        double cellLen=network.getEditorCellLength();
        double diagLen=cellLen*Math.sqrt(2);

        switch(currentTool){
            case H_ROAD    ->{previewAngle=(wx>=nx)?0:Math.PI;           previewLength=cellLen;}
            case V_ROAD    ->{previewAngle=(wy>=ny)?Math.PI/2:-Math.PI/2;previewLength=cellLen;}
            case ROAD_45   ->{
                // 45° = northeast (↗) or southwest (↙) based on cursor
                previewAngle=(wx>=nx&&wy<=ny)?-Math.PI/4:((wx<nx&&wy>ny)?3*Math.PI/4:(wx>=nx?-Math.PI/4:-3*Math.PI/4));
                // Snap to 4 diagonal directions
                double[]angles={-Math.PI/4, Math.PI/4, 3*Math.PI/4, -3*Math.PI/4};
                double cursorAngle=Math.atan2(wy-ny,wx-nx);
                double best=angles[0],bestDiff=Double.MAX_VALUE;
                for(double a:angles){double d=Math.abs(angleDiff(cursorAngle,a));if(d<bestDiff){bestDiff=d;best=a;}}
                previewAngle=best; previewLength=diagLen;
            }
            case ROAD_135  ->{
                // 135° family: SE/NW/NE(135)/SW(135)
                double[]angles={Math.PI/4, -Math.PI/4, 3*Math.PI/4, -3*Math.PI/4};
                double cursorAngle=Math.atan2(wy-ny,wx-nx);
                double best=angles[0],bestDiff=Double.MAX_VALUE;
                for(double a:angles){double d=Math.abs(angleDiff(cursorAngle,a));if(d<bestDiff){bestDiff=d;best=a;}}
                previewAngle=best; previewLength=diagLen;
            }
            case INTERSECTION->{
                Road out=null; for(Road r:network.getRoads())if(r.getFrom()==node){out=r;break;}
                previewAngle=(out!=null)?Math.atan2(-out.getDirY(),-out.getDirX()):0;
                previewLength=cellLen;
            }
        }
        previewValid=!network.isDirectionOccupied(node,previewAngle);
    }

    private double angleDiff(double a, double b){double d=a-b;while(d>Math.PI)d-=2*Math.PI;while(d<-Math.PI)d+=2*Math.PI;return d;}

    // ─────────────────────────────────────────────────────────────────────
    //  Click handler
    // ─────────────────────────────────────────────────────────────────────

    private void handleClick(int sx, int sy){
        if(currentTool==Tool.NONE)return;
        double[]w=s2w(sx,sy);

        // Flex intersection: place at cursor position, auto-connect nearby terminals
        if(currentTool==Tool.FLEX_INTERSECTION){
            double snapDist=network.getEditorCellLength()*0.6;
            int connected=network.placeFlexIntersection(w[0],w[1],snapDist);
            if(connected>=2){editCount++;repaint();}
            else showError("Không có đủ terminal gần đây!\nKéo các đoạn đường lại gần nhau rồi thử lại.");
            return;
        }

        if(snapTarget==null){snapTarget=findNearestSpawnZone(w[0],w[1],100.0/camZoom);if(snapTarget!=null)computePreview(snapTarget,w[0],w[1]);}
        if(snapTarget==null){showError("Không có terminal nào gần đây!\nDi chuyển chuột đến vùng xanh.");return;}

        if(!previewValid&&currentTool!=Tool.INTERSECTION){
            showError("Không thể đặt ở đây!\nHướng này đã bị chiếm hoặc không hợp lệ.\n(Mũi tên xanh chỉ các hướng trống)");return;
        }

        Node terminal=snapTarget.getNode();
        boolean ok=false;
        switch(currentTool){
            case H_ROAD,V_ROAD -> ok=(network.extendFromTerminal(terminal,previewAngle)!=null);
            case ROAD_45,ROAD_135 -> {
                double diagLen=network.getEditorCellLength()*Math.sqrt(2);
                ok=(network.extendFromTerminal(terminal,previewAngle,diagLen)!=null);
            }
            case INTERSECTION -> ok=!network.extendAsFullIntersection(terminal).isEmpty();
        }
        if(ok){editCount++;snapTarget=null;previewValid=false;repaint();}
        else showError("Không thể mở rộng theo hướng này.");
    }

    private void doUndo(){if(network.undoLastExtension()){editCount=Math.max(0,editCount-1);repaint();}else showError("Không còn gì để hoàn tác.");}
    private void showError(String msg){errorMsg=msg;errorExpiry=System.currentTimeMillis()+2500;repaint();}

    // ─────────────────────────────────────────────────────────────────────
    //  Rendering
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g){
        super.paintComponent(g);
        int W=getWidth(),H=getHeight(); if(W<=0||H<=0)return;
        if(buffer==null||buffer.getWidth()!=W||buffer.getHeight()!=H)
            buffer=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2=buffer.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(22,26,30));g2.fillRect(0,0,W,H);

        AffineTransform saved=g2.getTransform();
        g2.translate(W/2.0,H/2.0); g2.scale(camZoom,camZoom); g2.translate(-camX,-camY);

        renderer.render(g2,network);

        if(currentTool!=Tool.NONE) drawAvailableDirections(g2);
        drawSpawnZoneHighlights(g2);
        if(currentTool==Tool.FLEX_INTERSECTION) drawFlexCursor(g2);
        else if(currentTool!=Tool.NONE&&snapTarget!=null) drawPreview(g2);

        g2.setTransform(saved);
        drawHUD(g2,W,H);
        if(errorMsg!=null&&System.currentTimeMillis()<errorExpiry) drawError(g2,W,H);
        else errorMsg=null;
        g2.dispose();
        g.drawImage(buffer,0,0,null);
    }

    private void drawFlexCursor(Graphics2D g){
        double[]w=s2w(mouseX,mouseY);
        double snapDist=network.getEditorCellLength()*0.6;
        g.setColor(new Color(100,200,255,120));
        g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{6,4},0));
        g.draw(new Ellipse2D.Double(w[0]-snapDist,w[1]-snapDist,snapDist*2,snapDist*2));
        g.setStroke(new BasicStroke(1));
        g.setFont(new Font("SansSerif",Font.BOLD,14));g.setColor(new Color(100,220,255,230));
        g.drawString("⊕",(float)(w[0]-7),(float)(w[1]+6));
    }

    private void drawAvailableDirections(Graphics2D g){
        double[]angles={0,Math.PI,Math.PI/2,-Math.PI/2,-Math.PI/4,Math.PI/4,3*Math.PI/4,-3*Math.PI/4};
        for(SpawnZone sz:network.getSpawnZones()){
            Node node=sz.getNode(); double nx=node.getX(),ny=node.getY();
            for(double angle:angles){
                if(network.isDirectionOccupied(node,angle))continue;
                double ax=Math.cos(angle)*26,ay=Math.sin(angle)*26;
                g.setColor(new Color(100,220,120,130));
                g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(nx+ax*0.4,ny+ay*0.4,nx+ax,ny+ay));
                double px=-ay*0.25,py=ax*0.25;
                g.draw(new Line2D.Double(nx+ax,ny+ay,nx+ax*0.6+px,ny+ay*0.6+py));
                g.draw(new Line2D.Double(nx+ax,ny+ay,nx+ax*0.6-px,ny+ay*0.6-py));
                g.setStroke(new BasicStroke(1));
            }
        }
    }

    private void drawSpawnZoneHighlights(Graphics2D g){
        if(currentTool==Tool.NONE)return;
        for(SpawnZone sz:network.getSpawnZones()){
            Node n=sz.getNode(); boolean isSnap=(sz==snapTarget);
            float alpha=isSnap?0.92f:(blinkState?0.55f:0.35f); double radius=isSnap?24:17;
            g.setColor(new Color(0,210,90,(int)(alpha*160)));
            g.fill(new Ellipse2D.Double(n.getX()-radius*1.6,n.getY()-radius*1.6,radius*3.2,radius*3.2));
            g.setColor(new Color(0,220,100,(int)(alpha*230)));
            g.fill(new Ellipse2D.Double(n.getX()-radius,n.getY()-radius,radius*2,radius*2));
            g.setColor(Color.WHITE); g.setStroke(new BasicStroke(isSnap?2.5f:1.5f));
            g.draw(new Ellipse2D.Double(n.getX()-radius,n.getY()-radius,radius*2,radius*2)); g.setStroke(new BasicStroke(1));
            g.setFont(new Font("SansSerif",Font.BOLD,isSnap?14:10)); g.setColor(Color.WHITE);
            String icon=switch(currentTool){case H_ROAD->"━";case V_ROAD->"┃";case ROAD_45->"╱";case ROAD_135->"╲";case INTERSECTION->"✚";case FLEX_INTERSECTION->"⊕";default->"+";};
            FontMetrics fm=g.getFontMetrics(); g.drawString(icon,(float)(n.getX()-fm.stringWidth(icon)/2.0),(float)(n.getY()+fm.getAscent()/2.0-1));
        }
    }

    private void drawPreview(Graphics2D g){
        Node node=snapTarget.getNode(); double hw=network.getEditorHalfWidth(); boolean valid=previewValid;
        if(currentTool==Tool.INTERSECTION){
            Road out=null;for(Road r:network.getRoads())if(r.getFrom()==node){out=r;break;}
            if(out!=null){
                double cont=Math.atan2(-out.getDirY(),-out.getDirX());
                for(double angle:new double[]{cont,cont+Math.PI/2,cont-Math.PI/2})
                    drawRoadPreview(g,node,angle,hw,previewLength,!network.isDirectionOccupied(node,angle));
            }
        } else {
            drawRoadPreview(g,node,previewAngle,hw,previewLength,valid);
        }
    }

    private void drawRoadPreview(Graphics2D g,Node from,double angle,double hw,double len,boolean valid){
        double cA=Math.cos(angle),sA=Math.sin(angle),pX=-sA,pY=cA;
        double fx=from.getX(),fy=from.getY(),tx=fx+cA*len,ty=fy+sA*len;
        Color fill=valid?new Color(80,130,200,75):new Color(200,50,50,75);
        Color border=valid?new Color(120,180,255,190):new Color(255,80,80,220);
        Path2D road=new Path2D.Double();
        road.moveTo(fx-pX*hw,fy-pY*hw);road.lineTo(tx-pX*hw,ty-pY*hw);
        road.lineTo(tx+pX*hw,ty+pY*hw);road.lineTo(fx+pX*hw,fy+pY*hw);road.closePath();
        g.setColor(fill);g.fill(road);
        float[]dash={valid?10f:8f,valid?7f:6f};
        g.setColor(border);g.setStroke(new BasicStroke(valid?2f:2.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,dash,0));
        g.draw(road);g.setStroke(new BasicStroke(1));
        double nr=Node.ARRIVAL_RADIUS;
        g.setColor(valid?new Color(0,220,100,140):new Color(255,100,100,140));
        g.fill(new Ellipse2D.Double(tx-nr,ty-nr,nr*2,nr*2));
        g.setColor(valid?new Color(200,255,200,200):new Color(255,150,150,200));g.setStroke(new BasicStroke(1.8f));
        g.draw(new Ellipse2D.Double(tx-nr,ty-nr,nr*2,nr*2));g.setStroke(new BasicStroke(1));
        // ✓ or ✗
        g.setColor(valid?new Color(0,220,100,200):new Color(255,80,80,220));
        g.setFont(new Font("SansSerif",Font.BOLD,16)); String icon=valid?"✓":"✗";
        FontMetrics fm=g.getFontMetrics();
        g.drawString(icon,(float)(fx+cA*len/2-fm.stringWidth(icon)/2.0),(float)(fy+sA*len/2+fm.getAscent()/2.0-2));
    }

    private void drawHUD(Graphics2D g, int W, int H){
        g.setColor(new Color(0,0,0,160));g.fillRect(0,H-26,W,26);
        g.setFont(new Font("Monospaced",Font.PLAIN,11));g.setColor(new Color(160,210,160));
        String toolName=switch(currentTool){case H_ROAD->"━ Ngang";case V_ROAD->"┃ Dọc";case ROAD_45->"╱ 45°";case ROAD_135->"╲ 135°";case INTERSECTION->"✚ Ngã Tư";case FLEX_INTERSECTION->"⊕ Flex";default->"—";};
        g.drawString(String.format("🔧 Module  |  Công cụ: %-12s  |  Chỉnh sửa: %d  |  Nodes: %d  Spawn: %d  |  Zoom: %.2f×",toolName,editCount,network.getNodes().size(),network.getSpawnZones().size(),camZoom),8,H-8);
        if(currentTool!=Tool.NONE){boolean sv=snapTarget!=null&&previewValid;String hint=sv?"✓ Click để đặt":currentTool==Tool.FLEX_INTERSECTION?"Click gần các terminal để tạo ngã tư linh hoạt":"Di chuyển đến vùng xanh  •  Đỏ = không hợp lệ";g.setFont(new Font("SansSerif",Font.BOLD,11));g.setColor(sv?new Color(0,220,100):new Color(200,160,60));FontMetrics fm=g.getFontMetrics();g.drawString(hint,W-fm.stringWidth(hint)-10,20);}
        g.setFont(new Font("SansSerif",Font.PLAIN,10));g.setColor(new Color(85,93,105));
        g.drawString("[1]━  [2]┃  [3]╱45°  [4]╲135°  [5]✚Ngã4  [6]⊕Flex  [Ctrl+Z]Undo  [ESC]Bỏ chọn  [F]Fit",8,18);
    }

    private void drawError(Graphics2D g,int W,int H){
        String[]lines=errorMsg.split("\n");g.setFont(new Font("SansSerif",Font.BOLD,13));
        int padding=14,lineH=18,bw=0;
        for(String l:lines)bw=Math.max(bw,g.getFontMetrics().stringWidth(l));
        bw+=padding*2;int bh=lines.length*lineH+padding*2;int bx=(W-bw)/2,by=H/2-bh/2-40;
        g.setColor(new Color(0,0,0,120));g.fillRoundRect(bx+3,by+3,bw,bh,12,12);
        g.setColor(new Color(200,50,50,230));g.fillRoundRect(bx,by,bw,bh,12,12);
        g.setColor(new Color(255,150,150));g.setStroke(new BasicStroke(1.5f));g.drawRoundRect(bx,by,bw,bh,12,12);g.setStroke(new BasicStroke(1));
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,13));FontMetrics fm=g.getFontMetrics();
        for(int i=0;i<lines.length;i++){int lx=bx+(bw-fm.stringWidth(lines[i]))/2;g.drawString(lines[i],lx,by+padding+fm.getAscent()+i*lineH);}
    }

    // Camera
    private double[]s2w(int sx,int sy){return new double[]{(sx-getWidth()/2.0)/camZoom+camX,(sy-getHeight()/2.0)/camZoom+camY};}
    private void zoomAt(int sx,int sy,double f){double[]b=s2w(sx,sy);camZoom=Math.max(ZOOM_MIN,Math.min(ZOOM_MAX,camZoom*f));double[]a=s2w(sx,sy);camX-=(a[0]-b[0]);camY-=(a[1]-b[1]);}
    public void fitCamera(){List<Node>nodes=network.getNodes();if(nodes.isEmpty())return;double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-minX,maxY=-minY;for(Node n:nodes){minX=Math.min(minX,n.getX());minY=Math.min(minY,n.getY());maxX=Math.max(maxX,n.getX());maxY=Math.max(maxY,n.getY());}camX=(minX+maxX)/2;camY=(minY+maxY)/2;double nw=maxX-minX+260,nh=maxY-minY+260;camZoom=Math.max(ZOOM_MIN,Math.min(ZOOM_MAX,Math.min(getWidth()/nw,getHeight()/nh)));repaint();}
    private SpawnZone findNearestSpawnZone(double wx,double wy,double maxDist){SpawnZone best=null;double bd=maxDist;for(SpawnZone sz:network.getSpawnZones()){double d=sz.getNode().distanceTo(wx,wy);if(d<bd){bd=d;best=sz;}}return best;}
}
