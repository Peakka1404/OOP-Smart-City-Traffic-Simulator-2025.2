import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * ModuleEditorPanel v2:
 *  - Preview MÀU ĐỎ khi vị trí không hợp lệ (hướng đã bị chiếm)
 *  - Thông báo lỗi nổi khi người dùng cố đặt vào nơi không thể
 *  - Chỉ thị hướng còn trống tại mỗi terminal (mũi tên xanh)
 */
public class ModuleEditorPanel extends JPanel {

    public enum Tool { NONE, H_ROAD, V_ROAD, INTERSECTION }
    private Tool currentTool = Tool.NONE;

    private final RoadNetwork  network;
    private final SceneRenderer renderer = new SceneRenderer();

    // Camera
    private double camX=0,camY=0,camZoom=1.0;
    private static final double ZOOM_MIN=0.1,ZOOM_MAX=6.0,ZOOM_STEP=0.12;
    private Point dragStart; private double cxAtDrag,cyAtDrag;

    // Editor state
    private int mouseX,mouseY;
    private SpawnZone snapTarget=null;
    private double previewAngle=0;
    private boolean previewValid=false;
    private double blinkTimer=0; private boolean blinkState=false;
    private int editCount=0;

    // Error message float
    private String errorMsg=null;
    private long errorExpiry=0;

    private BufferedImage buffer;
    private JButton btnH,btnV,btnI;
    private Runnable onSimulate;

    public ModuleEditorPanel(RoadNetwork network, Runnable onSimulate){
        this.network=network; this.onSimulate=onSimulate;
        setBackground(new Color(22,26,30)); setFocusable(true);
        renderer.setShowNodes(true);
        setupInput();
        new javax.swing.Timer(50, e->{
            blinkTimer+=0.05; blinkState=(((int)(blinkTimer*2))%2==0);
            repaint();
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Toolbar
    // ─────────────────────────────────────────────────────────────────────

    public JPanel buildToolbar(){
        JPanel tb=new JPanel(new FlowLayout(FlowLayout.LEFT,8,6));
        tb.setBackground(new Color(28,32,40));
        tb.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(50,55,65)));

        JLabel lbl=new JLabel("  🔧  Chế Độ Module   |");
        lbl.setFont(new Font("SansSerif",Font.BOLD,13));
        lbl.setForeground(new Color(80,160,240)); tb.add(lbl);

        btnH=makeToolBtn("━  Đường Ngang","[1] Thêm đường ngang từ terminal",Tool.H_ROAD);
        btnV=makeToolBtn("┃  Đường Dọc",  "[2] Thêm đường dọc từ terminal",  Tool.V_ROAD);
        btnI=makeToolBtn("✚  Nút Giao",   "[3] Tạo ngã tư đầy đủ (3 nhánh)", Tool.INTERSECTION);
        tb.add(btnH); tb.add(btnV); tb.add(btnI);

        tb.add(makeSep());
        JButton btnUndo=makeActionBtn("↩  Undo",new Color(52,73,94));
        btnUndo.setToolTipText("Ctrl+Z");
        btnUndo.addActionListener(e->doUndo()); tb.add(btnUndo);
        tb.add(makeSep());

        JButton btnSim=makeActionBtn("▶  Bắt Đầu Mô Phỏng",new Color(39,174,96));
        btnSim.addActionListener(e->{ if(onSimulate!=null) onSimulate.run(); });
        tb.add(btnSim);

        JLabel hint=new JLabel("  [Click tool → Click vùng xanh để đặt]  [Scroll]=Zoom  [Drag-P]=Pan  [ESC]=Bỏ chọn");
        hint.setFont(new Font("SansSerif",Font.ITALIC,10));
        hint.setForeground(new Color(95,103,115)); tb.add(hint);
        return tb;
    }

    private JSeparator makeSep(){
        JSeparator s=new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(1,28)); s.setForeground(new Color(55,60,70)); return s;
    }

    private JButton makeToolBtn(String text,String tip,Tool tool){
        JButton btn=makeActionBtn(text,new Color(45,50,62));
        btn.setToolTipText(tip); btn.addActionListener(e->selectTool(tool)); return btn;
    }

    private JButton makeActionBtn(String text,Color bg){
        JButton btn=new JButton(text);
        btn.setBackground(bg); btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif",Font.BOLD,11));
        btn.setFocusPainted(false); btn.setBorderPainted(false); btn.setOpaque(true);
        btn.setBorder(BorderFactory.createEmptyBorder(5,10,5,10));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return btn;
    }

    private void selectTool(Tool tool){
        currentTool=(currentTool==tool)?Tool.NONE:tool;
        updateToolHighlights(); snapTarget=null; previewValid=false; repaint();
    }

    private void updateToolHighlights(){
        Color def=new Color(45,50,62), sel=new Color(52,152,219);
        if(btnH!=null) btnH.setBackground(currentTool==Tool.H_ROAD ?sel:def);
        if(btnV!=null) btnV.setBackground(currentTool==Tool.V_ROAD ?sel:def);
        if(btnI!=null) btnI.setBackground(currentTool==Tool.INTERSECTION?sel:def);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Input
    // ─────────────────────────────────────────────────────────────────────

    private void setupInput(){
        MouseAdapter ma=new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){
                requestFocusInWindow();
                if(e.getButton()==MouseEvent.BUTTON3||e.getButton()==MouseEvent.BUTTON2){
                    dragStart=e.getPoint(); cxAtDrag=camX; cyAtDrag=camY;
                }
            }
            @Override public void mouseReleased(MouseEvent e){
                if(e.getButton()==MouseEvent.BUTTON3||e.getButton()==MouseEvent.BUTTON2) dragStart=null;
            }
            @Override public void mouseDragged(MouseEvent e){
                if(dragStart!=null){
                    camX=cxAtDrag-(e.getX()-dragStart.x)/camZoom;
                    camY=cyAtDrag-(e.getY()-dragStart.y)/camZoom; repaint();
                }
            }
            @Override public void mouseClicked(MouseEvent e){
                if(e.getButton()==MouseEvent.BUTTON1){
                    if(e.getClickCount()==2){fitCamera();return;}
                    handleCanvasClick(e.getX(),e.getY());
                }
            }
            @Override public void mouseMoved(MouseEvent e){ mouseX=e.getX();mouseY=e.getY(); updatePreview(); }
        };
        addMouseListener(ma); addMouseMotionListener(ma);
        addMouseWheelListener(e->{
            double f=e.getWheelRotation()<0?1+ZOOM_STEP:1-ZOOM_STEP;
            zoomAt(e.getX(),e.getY(),f); repaint();
        });
        addKeyListener(new KeyAdapter(){
            @Override public void keyPressed(KeyEvent e){
                switch(e.getKeyCode()){
                    case KeyEvent.VK_ESCAPE->{ currentTool=Tool.NONE; updateToolHighlights(); snapTarget=null; repaint(); }
                    case KeyEvent.VK_F->fitCamera();
                    case KeyEvent.VK_LEFT, KeyEvent.VK_A->{camX-=30/camZoom;repaint();}
                    case KeyEvent.VK_RIGHT,KeyEvent.VK_D->{camX+=30/camZoom;repaint();}
                    case KeyEvent.VK_UP,   KeyEvent.VK_W->{camY-=30/camZoom;repaint();}
                    case KeyEvent.VK_DOWN, KeyEvent.VK_S->{camY+=30/camZoom;repaint();}
                    case KeyEvent.VK_EQUALS,KeyEvent.VK_PLUS->zoomAt(getWidth()/2,getHeight()/2,1+ZOOM_STEP*2);
                    case KeyEvent.VK_MINUS->zoomAt(getWidth()/2,getHeight()/2,1-ZOOM_STEP*2);
                    case KeyEvent.VK_Z->{ if((e.getModifiersEx()&InputEvent.CTRL_DOWN_MASK)!=0) doUndo(); }
                    case KeyEvent.VK_1->selectTool(Tool.H_ROAD);
                    case KeyEvent.VK_2->selectTool(Tool.V_ROAD);
                    case KeyEvent.VK_3->selectTool(Tool.INTERSECTION);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Preview + Snap
    // ─────────────────────────────────────────────────────────────────────

    private void updatePreview(){
        if(currentTool==Tool.NONE){snapTarget=null;previewValid=false;repaint();return;}
        double[] w=s2w(mouseX,mouseY);
        snapTarget=findNearestSpawnZone(w[0],w[1],90.0/camZoom);
        if(snapTarget!=null) computePreview(snapTarget,w[0],w[1]);
        else previewValid=false;
        repaint();
    }

    private void computePreview(SpawnZone sz, double wx, double wy){
        Node node=sz.getNode();
        double nx=node.getX(), ny=node.getY();
        switch(currentTool){
            case H_ROAD      -> previewAngle=(wx>=nx)?0:Math.PI;
            case V_ROAD      -> previewAngle=(wy>=ny)?Math.PI/2:-Math.PI/2;
            case INTERSECTION -> {
                Road out=null;
                for(Road r:network.getRoads()) if(r.getFrom()==node){out=r;break;}
                // Hướng tiếp tục = ngược chiều outgoing (đi xa mạng)
                previewAngle=(out!=null)?Math.atan2(-out.getDirY(),-out.getDirX()):0;
            }
        }
        previewValid=!network.isDirectionOccupied(node,previewAngle);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Click để đặt
    // ─────────────────────────────────────────────────────────────────────

    private void handleCanvasClick(int sx,int sy){
        if(currentTool==Tool.NONE) return;
        double[] w=s2w(sx,sy);
        if(snapTarget==null) snapTarget=findNearestSpawnZone(w[0],w[1],100.0/camZoom);
        if(snapTarget!=null) computePreview(snapTarget,w[0],w[1]);

        if(snapTarget==null){
            showError("Không có terminal nào gần đây!\nDi chuyển chuột đến vùng xanh.");
            return;
        }

        if(!previewValid){
            String toolName=switch(currentTool){
                case H_ROAD->"đường ngang"; case V_ROAD->"đường dọc";
                case INTERSECTION->"nút giao"; default->"";
            };
            showError("Không thể đặt "+toolName+" ở đây!\nHướng này đã bị chiếm hoặc không hợp lệ.");
            return;
        }

        Node terminal=snapTarget.getNode();
        boolean ok;
        switch(currentTool){
            case H_ROAD,V_ROAD -> ok=(network.extendFromTerminal(terminal,previewAngle)!=null);
            case INTERSECTION  -> ok=!network.extendAsFullIntersection(terminal).isEmpty();
            default -> ok=false;
        }

        if(ok){ editCount++; snapTarget=null; previewValid=false; repaint(); }
        else showError("Không thể mở rộng theo hướng này.");
    }

    private void doUndo(){
        if(network.undoLastExtension()){editCount=Math.max(0,editCount-1);repaint();}
        else showError("Không còn gì để hoàn tác.");
    }

    private void showError(String msg){
        errorMsg=msg; errorExpiry=System.currentTimeMillis()+2500; repaint();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Rendering
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g){
        super.paintComponent(g);
        int W=getWidth(),H=getHeight(); if(W<=0||H<=0) return;
        if(buffer==null||buffer.getWidth()!=W||buffer.getHeight()!=H)
            buffer=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2=buffer.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(22,26,30)); g2.fillRect(0,0,W,H);

        java.awt.geom.AffineTransform saved=g2.getTransform();
        g2.translate(W/2.0,H/2.0); g2.scale(camZoom,camZoom); g2.translate(-camX,-camY);

        renderer.render(g2,network);

        // Chỉ thị hướng còn trống tại terminals
        if(currentTool!=Tool.NONE) drawAvailableDirections(g2);

        // SpawnZone highlights
        drawSpawnZoneHighlights(g2);

        // Preview (xanh=hợp lệ, đỏ=không hợp lệ)
        if(currentTool!=Tool.NONE && snapTarget!=null) drawPreview(g2);

        g2.setTransform(saved);
        drawHUD(g2,W,H);

        // Error message overlay
        if(errorMsg!=null && System.currentTimeMillis()<errorExpiry) drawError(g2,W,H);
        else errorMsg=null;

        g2.dispose();
        g.drawImage(buffer,0,0,null);
    }

    /** Vẽ mũi tên nhỏ tại mỗi terminal cho thấy hướng nào còn trống. */
    private void drawAvailableDirections(Graphics2D g){
        double[] angles={0, Math.PI, Math.PI/2, -Math.PI/2};
        for(SpawnZone sz:network.getSpawnZones()){
            Node node=sz.getNode();
            double nx=node.getX(), ny=node.getY();
            for(double angle:angles){
                if(network.isDirectionOccupied(node,angle)) continue;
                double ax=Math.cos(angle)*28, ay=Math.sin(angle)*28;
                g.setColor(new Color(100,220,120,140));
                g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(nx+ax*0.4,ny+ay*0.4,nx+ax,ny+ay));
                // Đầu mũi tên nhỏ
                double px=-ay*0.25,py=ax*0.25;
                g.draw(new Line2D.Double(nx+ax,ny+ay,nx+ax*0.6+px,ny+ay*0.6+py));
                g.draw(new Line2D.Double(nx+ax,ny+ay,nx+ax*0.6-px,ny+ay*0.6-py));
                g.setStroke(new BasicStroke(1));
            }
        }
    }

    private void drawSpawnZoneHighlights(Graphics2D g){
        if(currentTool==Tool.NONE) return;
        for(SpawnZone sz:network.getSpawnZones()){
            Node n=sz.getNode();
            boolean isSnap=(sz==snapTarget);
            float alpha=isSnap?0.92f:(blinkState?0.55f:0.35f);
            double radius=isSnap?24:17;

            // Glow
            g.setColor(new Color(0,210,90,(int)(alpha*160)));
            g.fill(new Ellipse2D.Double(n.getX()-radius*1.6,n.getY()-radius*1.6,radius*3.2,radius*3.2));
            // Cercle
            g.setColor(new Color(0,220,100,(int)(alpha*230)));
            g.fill(new Ellipse2D.Double(n.getX()-radius,n.getY()-radius,radius*2,radius*2));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(isSnap?2.5f:1.5f));
            g.draw(new Ellipse2D.Double(n.getX()-radius,n.getY()-radius,radius*2,radius*2));
            g.setStroke(new BasicStroke(1));
            // Icône
            g.setFont(new Font("SansSerif",Font.BOLD,isSnap?14:10));
            g.setColor(Color.WHITE);
            String icon=switch(currentTool){
                case H_ROAD->"━"; case V_ROAD->"┃"; case INTERSECTION->"✚"; default->"+";
            };
            FontMetrics fm=g.getFontMetrics();
            g.drawString(icon,(float)(n.getX()-fm.stringWidth(icon)/2.0),(float)(n.getY()+fm.getAscent()/2.0-1));
        }
    }

    /** Preview XANH si valide, ROUGE si invalide. */
    private void drawPreview(Graphics2D g){
        Node node=snapTarget.getNode();
        double hw=network.getEditorHalfWidth();
        double len=network.getEditorCellLength();
        boolean valid=previewValid;

        if(currentTool==Tool.INTERSECTION){
            Road out=null;
            for(Road r:network.getRoads()) if(r.getFrom()==node){out=r;break;}
            if(out!=null){
                double contAngle=Math.atan2(-out.getDirY(),-out.getDirX());
                double[] angles={contAngle, contAngle+Math.PI/2, contAngle-Math.PI/2};
                for(double angle:angles)
                    drawRoadPreview(g,node,angle,hw,len,!network.isDirectionOccupied(node,angle));
            }
        } else {
            drawRoadPreview(g,node,previewAngle,hw,len,valid);
        }
    }

    private void drawRoadPreview(Graphics2D g,Node from,double angle,double hw,double len,boolean valid){
        double cA=Math.cos(angle),sA=Math.sin(angle),pX=-sA,pY=cA;
        double fx=from.getX(),fy=from.getY();
        double tx=fx+cA*len, ty=fy+sA*len;

        // Couleurs selon validité
        Color fillColor  = valid ? new Color(80,130,200,75) : new Color(200,50,50,75);
        Color borderColor= valid ? new Color(120,180,255,190) : new Color(255,80,80,220);
        Color nodeColor  = valid ? new Color(0,220,100,150) : new Color(255,100,100,150);

        // Route
        Path2D road=new Path2D.Double();
        road.moveTo(fx-pX*hw,fy-pY*hw);
        road.lineTo(tx-pX*hw,ty-pY*hw);
        road.lineTo(tx+pX*hw,ty+pY*hw);
        road.lineTo(fx+pX*hw,fy+pY*hw);
        road.closePath();
        g.setColor(fillColor); g.fill(road);
        float[] dash={valid?10f:8f, valid?7f:6f};
        g.setColor(borderColor);
        g.setStroke(new BasicStroke(valid?2f:2.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,dash,0));
        g.draw(road); g.setStroke(new BasicStroke(1));

        // Nouveau terminal
        double nr=Node.ARRIVAL_RADIUS;
        g.setColor(nodeColor);
        g.fill(new Ellipse2D.Double(tx-nr,ty-nr,nr*2,nr*2));
        g.setColor(valid?new Color(200,255,200,200):new Color(255,150,150,200));
        g.setStroke(new BasicStroke(1.8f));
        g.draw(new Ellipse2D.Double(tx-nr,ty-nr,nr*2,nr*2));
        g.setStroke(new BasicStroke(1));

        // Icône ✓ ou ✗ au centre
        double mx=fx+cA*len/2, my=fy+sA*len/2;
        g.setColor(valid?new Color(0,220,100,200):new Color(255,80,80,220));
        g.setFont(new Font("SansSerif",Font.BOLD,16));
        String icon=valid?"✓":"✗";
        FontMetrics fm=g.getFontMetrics();
        g.drawString(icon,(float)(mx-fm.stringWidth(icon)/2.0),(float)(my+fm.getAscent()/2.0-2));
    }

    private void drawHUD(Graphics2D g, int W, int H){
        g.setColor(new Color(0,0,0,160)); g.fillRect(0,H-26,W,26);
        g.setFont(new Font("Monospaced",Font.PLAIN,11));
        g.setColor(new Color(160,210,160));
        String toolName=switch(currentTool){
            case H_ROAD->"━ Đường Ngang"; case V_ROAD->"┃ Đường Dọc";
            case INTERSECTION->"✚ Nút Giao"; default->"—";
        };
        g.drawString(String.format("🔧 Module  |  Công cụ: %-14s  |  Chỉnh sửa: %d  |  Nodes: %d  Spawn: %d  |  Zoom: %.2f×",
            toolName,editCount,network.getNodes().size(),network.getSpawnZones().size(),camZoom),8,H-8);

        if(currentTool!=Tool.NONE){
            boolean sv=snapTarget!=null&&previewValid;
            String hint=sv?"✓ Click để đặt":"Di chuyển đến vùng xanh  •  Đỏ=không thể đặt";
            g.setFont(new Font("SansSerif",Font.BOLD,11));
            g.setColor(sv?new Color(0,220,100):new Color(200,160,60));
            FontMetrics fm=g.getFontMetrics();
            g.drawString(hint,W-fm.stringWidth(hint)-10,20);
        }
        g.setFont(new Font("SansSerif",Font.PLAIN,10));
        g.setColor(new Color(85,93,105));
        g.drawString("[1]H  [2]V  [3]✚  [Ctrl+Z]Undo  [ESC]Bỏ chọn  [F]Fit  [WASD]Pan",8,18);
    }

    private void drawError(Graphics2D g, int W, int H){
        String[] lines=errorMsg.split("\n");
        int padding=14, lineH=18;
        int bw=0;
        g.setFont(new Font("SansSerif",Font.BOLD,13));
        for(String l:lines) bw=Math.max(bw,g.getFontMetrics().stringWidth(l));
        bw+=padding*2;
        int bh=lines.length*lineH+padding*2;
        int bx=(W-bw)/2, by=H/2-bh/2-40;
        // Shadow
        g.setColor(new Color(0,0,0,120));
        g.fillRoundRect(bx+3,by+3,bw,bh,12,12);
        // Background
        g.setColor(new Color(200,50,50,230));
        g.fillRoundRect(bx,by,bw,bh,12,12);
        g.setColor(new Color(255,150,150));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(bx,by,bw,bh,12,12);
        g.setStroke(new BasicStroke(1));
        // Text
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,13));
        FontMetrics fm=g.getFontMetrics();
        for(int i=0;i<lines.length;i++){
            int lx=bx+(bw-fm.stringWidth(lines[i]))/2;
            g.drawString(lines[i],lx,by+padding+fm.getAscent()+i*lineH);
        }
    }

    // Camera
    private double[] s2w(int sx,int sy){
        return new double[]{(sx-getWidth()/2.0)/camZoom+camX,(sy-getHeight()/2.0)/camZoom+camY};
    }
    private void zoomAt(int sx,int sy,double f){
        double[]b=s2w(sx,sy); camZoom=Math.max(ZOOM_MIN,Math.min(ZOOM_MAX,camZoom*f));
        double[]a=s2w(sx,sy); camX-=(a[0]-b[0]); camY-=(a[1]-b[1]);
    }
    public void fitCamera(){
        List<Node> nodes=network.getNodes(); if(nodes.isEmpty()) return;
        double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-minX,maxY=-minY;
        for(Node n:nodes){minX=Math.min(minX,n.getX());minY=Math.min(minY,n.getY());
                          maxX=Math.max(maxX,n.getX());maxY=Math.max(maxY,n.getY());}
        camX=(minX+maxX)/2; camY=(minY+maxY)/2;
        double nw=maxX-minX+260,nh=maxY-minY+260;
        camZoom=Math.max(ZOOM_MIN,Math.min(ZOOM_MAX,Math.min(getWidth()/nw,getHeight()/nh)));
        repaint();
    }

    private SpawnZone findNearestSpawnZone(double wx,double wy,double maxDist){
        SpawnZone best=null; double bd=maxDist;
        for(SpawnZone sz:network.getSpawnZones()){
            double d=sz.getNode().distanceTo(wx,wy);
            if(d<bd){bd=d;best=sz;}
        }
        return best;
    }
}
