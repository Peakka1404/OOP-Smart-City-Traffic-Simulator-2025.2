import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.Collection;

public class ControlPanel extends JPanel {

    private static final Color BG     = new Color(28, 30, 36);
    private static final Color FG     = new Color(210, 215, 225);
    private static final Color FG_DIM = new Color(130, 140, 155);
    private static final Color ACCENT = new Color( 80, 160, 240);
    private static final Color ACCENT2= new Color( 60, 210, 140);

    private final SimulationPanel simPanel;
    private final SceneRenderer   renderer;
    private final SoundManager    sound;
    private final RoadNetwork     network;

    private JLabel lblVehicles, lblSpawned, lblArrived, lblFps;

    public ControlPanel(SimulationPanel sim, SceneRenderer rend,
                        SoundManager snd, RoadNetwork net) {
        this.simPanel=sim; this.renderer=rend; this.sound=snd; this.network=net;
        setPreferredSize(new Dimension(220,600));
        setBackground(BG);
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(8,8,8,8));
        build();
        new Timer(300, e->updateStats()).start();
    }

    private void build() {
        addTitle("🚦 Traffic Simulation");

        // ── Điều khiển ────────────────────────────────────────────────────
        addSection("Điều khiển");
        JButton btnPause=makeButton("⏸  Tạm dừng",ACCENT);
        btnPause.addActionListener(e->{
            simPanel.togglePause();
            btnPause.setText(simPanel.isPaused()?"▶  Tiếp tục":"⏸  Tạm dừng");
        });
        addFull(btnPause); addGap(3);
        JButton btnReset=makeButton("🔄  Reset",new Color(180,70,50));
        btnReset.addActionListener(e->simPanel.resetSim());
        addFull(btnReset); addGap(3);
        JButton btnSpawn=makeButton("➕  Sinh 1 xe",ACCENT2);
        btnSpawn.addActionListener(e->simPanel.spawnOne());
        addFull(btnSpawn);

        // ── Mật độ ────────────────────────────────────────────────────────
        addSection("Mật độ giao thông");
        JLabel lblD=makeLabel("Spawn mỗi 1.5s");add(lblD);
        JSlider slD=makeSlider(2,60,15);
        slD.addChangeListener(e->{
            double v=slD.getValue()/10.0; simPanel.setSpawnInterval(v);
            lblD.setText(String.format("Spawn mỗi %.1fs",v));
        });
        addFull(slD);
        JCheckBox cbAuto=makeCheckBox("Tự động sinh xe",true);
        cbAuto.addActionListener(e->simPanel.setAutoSpawn(cbAuto.isSelected()));
        add(cbAuto);

        // ── Tốc độ tối đa ─────────────────────────────────────────────────
        addSection("Tốc độ tối đa (px/s)");
        JLabel lblSpd=makeLabel("Max speed: 110 px/s"); add(lblSpd);
        JSlider slSpd=makeSlider(20,300,110);
        slSpd.addChangeListener(e->{
            int spd=slSpd.getValue();
            network.setDefaultMaxSpeed(spd);
            lblSpd.setText("Max speed: "+spd+" px/s");
        });
        addFull(slSpd);

        // ── Gia tốc tối đa ────────────────────────────────────────────────
        addSection("Gia tốc tối đa (px/s²)");
        int defaultAccel = (int)(110 * 2.2);  // default = maxSpeed * 2.2
        JLabel lblAcc=makeLabel("Max accel: "+defaultAccel+" px/s²"); add(lblAcc);
        // Range: 50 → 800 px/s²
        JSlider slAcc=makeSlider(50,800,defaultAccel);
        slAcc.addChangeListener(e->{
            int acc=slAcc.getValue();
            network.setAccelerationForAll(acc);
            lblAcc.setText("Max accel: "+acc+" px/s²");
        });
        addFull(slAcc);

        // ── Khoảng cách giữ ───────────────────────────────────────────────
        addSection("Khoảng cách giữ với xe trước");
        JLabel lblFD=makeLabel("Default: 2/3 chiều dài xe"); add(lblFD);
        JSlider slFD=makeSlider(5,80,17);
        slFD.addChangeListener(e->{
            int d=slFD.getValue();
            lblFD.setText("Khoảng cách: "+d+" px");
            for(Vehicle v:network.getVehicles()) v.setFollowDistance(d);
        });
        addFull(slFD);

        // ── Đèn giao thông ────────────────────────────────────────────────
        addSection("Đèn giao thông");
        Collection<IntersectionController> ics=network.getAllIntersectionControllers();
        JLabel lblGreen=makeLabel("Thời gian XANH: 8s"); add(lblGreen);
        JSlider slGreen=makeSlider(2,30,8);
        slGreen.addChangeListener(e->{
            int t=slGreen.getValue(); lblGreen.setText("Thời gian XANH: "+t+"s");
            ics.forEach(ic->ic.setGreenTime(t));
        });
        addFull(slGreen); addGap(3);
        JLabel lblYellow=makeLabel("Thời gian VÀNG: 2.5s"); add(lblYellow);
        JSlider slYellow=makeSlider(1,10,5);
        slYellow.addChangeListener(e->{
            double t=slYellow.getValue()*0.5;
            lblYellow.setText(String.format("Thời gian VÀNG: %.1fs",t));
            ics.forEach(ic->ic.setYellowTime(t));
        });
        addFull(slYellow);

        // ── Hiển thị ──────────────────────────────────────────────────────
        addSection("Chế độ hiển thị xe");
        ButtonGroup mg=new ButtonGroup();
        JRadioButton rbG=makeRadio("🖼  Đồ họa",true,mg);
        JRadioButton rbB=makeRadio("⬛  Cơ bản",false,mg);
        rbG.addActionListener(e->renderer.setVehicleMode(SceneRenderer.VehicleMode.GRAPHIC));
        rbB.addActionListener(e->renderer.setVehicleMode(SceneRenderer.VehicleMode.BASIC));
        add(rbG); addGap(2); add(rbB); addGap(4);
        JCheckBox cbHB=makeCheckBox("Hiện hitbox",false);
        JCheckBox cbPT=makeCheckBox("Hiện đường đi A*",false);
        JCheckBox cbND=makeCheckBox("Hiện nút giao thông",true);
        cbHB.addActionListener(e->renderer.setShowHitbox(cbHB.isSelected()));
        cbPT.addActionListener(e->renderer.setShowPath(cbPT.isSelected()));
        cbND.addActionListener(e->renderer.setShowNodes(cbND.isSelected()));
        add(cbHB); addGap(2); add(cbPT); addGap(2); add(cbND);

        // ── Âm thanh ──────────────────────────────────────────────────────
        addSection("Âm thanh");
        JCheckBox cbSnd=makeCheckBox("🔊 Bật âm thanh",true);
        cbSnd.addActionListener(e->sound.setEnabled(cbSnd.isSelected()));
        add(cbSnd);

        // ── Legend ────────────────────────────────────────────────────────
        addSection("Màu trạng thái xe");
        Object[][] legend={
            {new Color(52,152,219),"Đang chạy"},
            {new Color(230,126, 34),"Giảm tốc"},
            {new Color(231, 76, 60),"Chờ đèn"},
            {new Color(241,196, 15),"Nhường"},
            {new Color(155, 89,182),"Đang vượt"},
        };
        for(Object[] row:legend){
            JLabel l=new JLabel("  "+(String)row[1]);
            l.setOpaque(true); l.setBackground((Color)row[0]);
            l.setForeground(Color.WHITE);
            l.setFont(new Font("SansSerif",Font.BOLD,10));
            l.setBorder(new EmptyBorder(1,6,1,4));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            l.setMaximumSize(new Dimension(10000,18));
            add(l); addGap(1);
        }

        // ── Stats ──────────────────────────────────────────────────────────
        addSection("Thống kê");
        lblVehicles=makeStatLabel("Xe đang chạy: 0");
        lblSpawned =makeStatLabel("Đã sinh:      0");
        lblArrived =makeStatLabel("Đã đến đích:  0");
        lblFps     =makeStatLabel("FPS:          --");
        add(lblVehicles);add(lblSpawned);add(lblArrived);add(lblFps);

        // ── Phím tắt ──────────────────────────────────────────────────────
        addSection("Phím tắt");
        addInfo("[Space] Tạm dừng/Tiếp tục");
        addInfo("[R]     Reset mô phỏng");
        addInfo("[S]     Sinh 1 xe ngẫu nhiên");
        addInfo("[F]     Fit camera");
        addInfo("[WASD]  Pan camera");
        addInfo("[Scroll] Zoom tại con trỏ");
        addInfo("[Drag-P] Pan chuột phải");

        add(Box.createVerticalGlue());
        addGap(4);
        JLabel credit=new JLabel("Traffic Sim v6.0");
        credit.setForeground(new Color(55,60,70));
        credit.setFont(new Font("SansSerif",Font.ITALIC,10));
        credit.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(credit);
    }

    private void updateStats(){
        lblVehicles.setText(String.format("Xe đang chạy: %d",network.getVehicleCount()));
        lblSpawned.setText( String.format("Đã sinh:      %d",simPanel.getTotalSpawned()));
        lblArrived.setText( String.format("Đã đến đích:  %d",simPanel.getTotalArrived()));
        lblFps.setText(     String.format("FPS:          %d",simPanel.getFps()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private void addTitle(String t){
        JLabel l=new JLabel(t);l.setFont(new Font("SansSerif",Font.BOLD,14));
        l.setForeground(ACCENT);l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(new EmptyBorder(0,0,6,0));add(l);
    }
    private void addSection(String t){
        addGap(5);
        JSeparator sep=new JSeparator();sep.setForeground(new Color(55,60,75));
        sep.setMaximumSize(new Dimension(10000,1));add(sep);addGap(3);
        JLabel l=new JLabel(t);l.setFont(new Font("SansSerif",Font.BOLD,11));
        l.setForeground(ACCENT);l.setAlignmentX(Component.LEFT_ALIGNMENT);add(l);addGap(3);
    }
    private void addInfo(String t){
        JLabel l=new JLabel(t);l.setFont(new Font("Monospaced",Font.PLAIN,10));
        l.setForeground(FG_DIM);l.setAlignmentX(Component.LEFT_ALIGNMENT);add(l);
    }
    private void addGap(int h){add(Box.createVerticalStrut(h));}
    private void addFull(JComponent c){
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(10000,c.getPreferredSize().height));add(c);
    }
    private JButton makeButton(String t,Color bg){
        JButton b=new JButton(t);b.setBackground(bg);b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif",Font.BOLD,11));b.setFocusPainted(false);
        b.setBorderPainted(false);b.setOpaque(true);
        b.setBorder(BorderFactory.createEmptyBorder(5,8,5,8));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return b;
    }
    private JSlider makeSlider(int min,int max,int val){
        JSlider s=new JSlider(min,max,val);s.setBackground(BG);s.setForeground(FG);
        s.setPaintTicks(false);s.setMaximumSize(new Dimension(10000,26));
        s.setAlignmentX(Component.LEFT_ALIGNMENT);return s;
    }
    private JLabel makeLabel(String t){
        JLabel l=new JLabel(t);l.setFont(new Font("SansSerif",Font.PLAIN,10));
        l.setForeground(FG);l.setAlignmentX(Component.LEFT_ALIGNMENT);return l;
    }
    private JLabel makeStatLabel(String t){
        JLabel l=new JLabel(t);l.setFont(new Font("Monospaced",Font.PLAIN,11));
        l.setForeground(ACCENT2);l.setAlignmentX(Component.LEFT_ALIGNMENT);return l;
    }
    private JRadioButton makeRadio(String t,boolean sel,ButtonGroup g){
        JRadioButton b=new JRadioButton(t,sel);b.setBackground(BG);b.setForeground(FG);
        b.setFont(new Font("SansSerif",Font.PLAIN,11));b.setFocusPainted(false);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);g.add(b);return b;
    }
    private JCheckBox makeCheckBox(String t,boolean sel){
        JCheckBox b=new JCheckBox(t,sel);b.setBackground(BG);b.setForeground(FG);
        b.setFont(new Font("SansSerif",Font.PLAIN,11));b.setFocusPainted(false);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);return b;
    }
}
