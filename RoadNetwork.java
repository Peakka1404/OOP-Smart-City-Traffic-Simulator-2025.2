import java.util.*;

/**
 * RoadNetwork — Đồ thị đường + A* + IntersectionController + SpawnZone.
 */
public class RoadNetwork {

    private final List<Node>                nodes      = new ArrayList<>();
    private final List<Road>                roads      = new ArrayList<>();
    private final List<Vehicle>             vehicles   = new ArrayList<>();
    private final List<SpawnZone>           spawnZones = new ArrayList<>();
    private final Map<Node, IntersectionController> intersections = new LinkedHashMap<>();

    private final Random random;
    private int vehicleCounter = 0;

    private int    maxVehicles          = 60;
    private double defaultVehicleWidth  = 11.0;
    private double defaultVehicleHeight = 20.0;
    private double defaultMaxSpeed      = 100.0;

    public RoadNetwork()           { this(new Random()); }
    public RoadNetwork(Random rng) { this.random = rng; }

    // ─────────────────────────────────────────────────────────────────────
    //  Build graph
    // ─────────────────────────────────────────────────────────────────────

    public void addNode(Node n) {
        for (Node e : nodes)
            if (e.getId().equals(n.getId()))
                throw new IllegalArgumentException("Duplicate node: " + n.getId());
        nodes.add(n);
    }

    public void addRoad(Road r) {
        if (!nodes.contains(r.getFrom())) throw new IllegalStateException("Unknown from: " + r.getFrom().getId());
        if (!nodes.contains(r.getTo()))   throw new IllegalStateException("Unknown to: "   + r.getTo().getId());
        roads.add(r);
        r.getFrom().registerOutgoingRoad(r);
    }

    public void addBidirectionalRoad(String idAB, String idBA, Node a, Node b,
                                      double laneWidth, int laneCount) {
        addRoad(new Road(idAB, a, b, laneWidth, laneCount));
        addRoad(new Road(idBA, b, a, laneWidth, laneCount));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  IntersectionController
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Tạo IntersectionController cho một node ngã tư.
     * Gọi SAU khi đã addRoad cho tất cả đường kết nối với node này.
     *
     * @param node      node ngã tư
     * @param halfWidth bán kính hộp ngã tư (thường = laneWidth * laneCount)
     */
    public IntersectionController addIntersection(Node node, double halfWidth) {
        IntersectionController ic = new IntersectionController(node, halfWidth);

        // Đăng ký tất cả roads có to == node (xe đi vào từ đó)
        for (Road r : roads) {
            if (r.getTo() == node) ic.registerApproachRoad(r);
        }
        ic.initPhases();
        intersections.put(node, ic);
        return ic;
    }

    public IntersectionController getIntersectionController(Node node) {
        return intersections.get(node);
    }

    public Collection<IntersectionController> getAllIntersectionControllers() {
        return intersections.values();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SpawnZone
    // ─────────────────────────────────────────────────────────────────────

    public void addSpawnZone(Node terminal, Road stubRoad) {
        spawnZones.add(new SpawnZone(terminal, stubRoad));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  A*
    // ─────────────────────────────────────────────────────────────────────

    public List<Node> findPath(Node start, Node goal) {
        if (start == goal) return Collections.singletonList(start);

        Map<Node,Double> g = new HashMap<>(), f = new HashMap<>();
        Map<Node,Node>   came = new HashMap<>();
        for (Node n : nodes) { g.put(n, Double.MAX_VALUE); f.put(n, Double.MAX_VALUE); }
        g.put(start, 0.0);
        f.put(start, start.distanceTo(goal));

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingDouble(n -> f.getOrDefault(n, Double.MAX_VALUE)));
        Set<Node> openSet = new HashSet<>(), closed = new HashSet<>();
        open.add(start); openSet.add(start);

        while (!open.isEmpty()) {
            Node cur = open.poll(); openSet.remove(cur);
            if (cur == goal) return buildPath(came, goal);
            closed.add(cur);
            for (Road r : cur.getOutgoingRoads()) {
                Node nb = r.getTo();
                if (closed.contains(nb)) continue;
                double tg = g.get(cur) + r.getLength();
                if (tg < g.get(nb)) {
                    came.put(nb, cur);
                    g.put(nb, tg);
                    f.put(nb, tg + nb.distanceTo(goal));
                    if (!openSet.contains(nb)) { open.add(nb); openSet.add(nb); }
                }
            }
        }
        return null;
    }

    private List<Node> buildPath(Map<Node,Node> came, Node goal) {
        LinkedList<Node> p = new LinkedList<>();
        Node cur = goal;
        while (came.containsKey(cur)) { p.addFirst(cur); cur = came.get(cur); }
        p.addFirst(cur);
        return new ArrayList<>(p);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Road lookup helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Tìm road từ `from` đến `to`. Trả về null nếu không có. */
    public Road findRoadBetween(Node from, Node to) {
        for (Road r : roads)
            if (r.getFrom() == from && r.getTo() == to) return r;
        return null;
    }

    /** Tìm Road hiện tại của xe (ưu tiên đoạn đang đi trong path). */
    public Road findRoadForVehicle(Vehicle v) {
        if (v.isArrived()) return null;
        int idx = v.getPathIndex();
        List<Node> path = v.getPath();

        if (idx > 0 && idx < path.size()) {
            Node from = path.get(idx-1), to = path.get(idx);
            for (Road r : roads) {
                if (r.getFrom()==from && r.getTo()==to
                        && r.containsPoint(v.getX(), v.getY(), Node.ARRIVAL_RADIUS))
                    return r;
            }
        }
        // Fallback: road gần nhất trong bán kính hợp lý
        Road best = null; double bestD = Double.MAX_VALUE;
        for (Road r : roads) {
            double[] cp = r.closestPointOnRoad(v.getX(), v.getY());
            double dx = cp[0]-v.getX(), dy = cp[1]-v.getY();
            double d  = Math.sqrt(dx*dx+dy*dy);
            if (d < bestD && d < r.getHalfWidth()+v.getHitboxRadius()) { bestD=d; best=r; }
        }
        return best;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Spawn / Despawn
    // ─────────────────────────────────────────────────────────────────────

    public Vehicle spawnVehicle() {
        return spawnVehicle(defaultVehicleWidth, defaultVehicleHeight, defaultMaxSpeed);
    }

    public Vehicle spawnVehicle(double w, double h, double maxSpd) {
        if (vehicles.size() >= maxVehicles) return null;
        if (spawnZones.size() < 2) return null;

        for (int attempt = 0; attempt < 30; attempt++) {
            SpawnZone szA = spawnZones.get(random.nextInt(spawnZones.size()));
            SpawnZone szB = spawnZones.get(random.nextInt(spawnZones.size()));
            if (szA.getNode() == szB.getNode()) continue;

            List<Node> path = findPath(szA.getNode(), szB.getNode());
            if (path == null || path.size() < 2) continue;

            double[] pt = szA.randomSpawnPoint(random);
            String id = String.format("V%03d", vehicleCounter++);
            Vehicle v = new Vehicle(id, pt[0], pt[1], w, h, maxSpd, path);
            vehicles.add(v);
            return v;
        }
        return null;
    }

    public void addVehicle(Vehicle v)    { if (vehicles.size() < maxVehicles) vehicles.add(v); }
    public void removeVehicle(Vehicle v) { vehicles.remove(v); }
    public void clearVehicles()          { vehicles.clear(); }

    // ─────────────────────────────────────────────────────────────────────
    //  Update
    // ─────────────────────────────────────────────────────────────────────

    public void update(double dt) {
        // Cập nhật tất cả IntersectionController
        for (IntersectionController ic : intersections.values()) ic.update(dt);

        // Cập nhật xe
        List<Vehicle> snap = new ArrayList<>(vehicles);
        for (Vehicle v : snap) v.update(dt, snap, this);
        vehicles.removeIf(Vehicle::isArrived);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Queries
    // ─────────────────────────────────────────────────────────────────────

    public Node findNode(String id) {
        for (Node n : nodes) if (n.getId().equals(id)) return n; return null;
    }

    public int getVehicleCount() { return vehicles.size(); }

    public List<Node>      getNodes()      { return Collections.unmodifiableList(nodes); }
    public List<Road>      getRoads()      { return Collections.unmodifiableList(roads); }
    public List<Vehicle>   getVehicles()   { return Collections.unmodifiableList(vehicles); }
    public List<SpawnZone> getSpawnZones() { return Collections.unmodifiableList(spawnZones); }

    // ─────────────────────────────────────────────────────────────────────
    //  Config
    // ─────────────────────────────────────────────────────────────────────

    public int    getMaxVehicles()                      { return maxVehicles; }
    public void   setMaxVehicles(int v)                 { maxVehicles = v; }
    public double getDefaultVehicleWidth()              { return defaultVehicleWidth; }
    public double getDefaultVehicleHeight()             { return defaultVehicleHeight; }
    public double getDefaultMaxSpeed()                  { return defaultMaxSpeed; }
    public void   setDefaultVehicleSize(double w,double h){ defaultVehicleWidth=w; defaultVehicleHeight=h; }
    public void   setDefaultMaxSpeed(double s)          { defaultMaxSpeed = s; }

    /** Chỉnh gia tốc cho tất cả xe đang chạy + mặc định cho xe mới. */
    public void setAccelerationForAll(double accel) {
        for (Vehicle v : vehicles) v.setAcceleration(accel);
    }

    @Override public String toString() {
        return String.format("RoadNetwork[nodes=%d roads=%d intersections=%d vehicles=%d spawnZones=%d]",
                nodes.size(), roads.size(), intersections.size(), vehicles.size(), spawnZones.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Module Editor — phương thức chỉnh sửa mạng lưới động
    // ═════════════════════════════════════════════════════════════════════

    /** Chiều dài mỗi ô (cell) và halfWidth đường — dùng cho editor. */
    private double editorCellLength = 460;
    private double editorHalfWidth  = 90;    // = road.getHalfWidth()
    private int    editorLaneCount  = 2;

    public void setEditorParams(double cellLen, double halfWidth, int laneCount) {
        this.editorCellLength = cellLen;
        this.editorHalfWidth  = halfWidth;
        this.editorLaneCount  = laneCount;
    }
    public double getEditorCellLength() { return editorCellLength; }
    public double getEditorHalfWidth()  { return editorHalfWidth; }

    /** Tìm SpawnZone tại node cụ thể. */
    public SpawnZone getSpawnZoneForNode(Node node) {
        for (SpawnZone sz : spawnZones) if (sz.getNode() == node) return sz;
        return null;
    }

    /** Xoá SpawnZone tại node. */
    public void removeSpawnZone(Node node) {
        spawnZones.removeIf(sz -> sz.getNode() == node);
    }

    /**
     * Mở rộng một con đường từ terminal node theo góc angle (radian).
     * Tự động:
     *   - Tạo node mới tại đầu xa
     *   - Thêm đường 2 chiều
     *   - Xoá SpawnZone cũ ở terminal
     *   - Thêm SpawnZone mới ở node đầu xa
     *   - Nâng cấp terminal thành ngã tư nếu có ≥ 2 đường
     *
     * @return node đầu xa mới, hoặc null nếu hướng đã bị chiếm / trùng node
     */
    /** Overload with custom length for diagonal roads (45°/135°). */
    public Node extendFromTerminal(Node terminal, double angle, double length) {
        double saved = editorCellLength; editorCellLength = length;
        Node r = extendFromTerminal(terminal, angle); editorCellLength = saved; return r;
    }

    public Node extendFromTerminal(Node terminal, double angle) {
        double len = editorCellLength;
        double newX = terminal.getX() + Math.cos(angle) * len;
        double newY = terminal.getY() + Math.sin(angle) * len;

        // FIX: connect to existing node if target position is near one (facing roads)
        for (Node existing : nodes) {
            if (existing != terminal && existing.distanceTo(newX, newY) < 55) {
                return connectTerminals(terminal, existing);
            }
        }

        // Check direction not already occupied
        if (isDirectionOccupied(terminal, angle)) return null;

        // Create new node
        String newId = "T" + nodes.size();
        Node newNode = new Node(newId, newX, newY);
        addNode(newNode);

        // laneWidth raw: halfWidth = laneWidth*laneCount/2 → laneWidth = halfWidth*2/laneCount
        double laneWidth = editorHalfWidth * 2.0 / editorLaneCount;
        String fwd = "R_" + terminal.getId() + "_" + newNode.getId();
        String bwd = "R_" + newNode.getId() + "_" + terminal.getId();
        addBidirectionalRoad(fwd, bwd, terminal, newNode, laneWidth, editorLaneCount);

        // SpawnZones
        removeSpawnZone(terminal);
        Road stubRoad = findRoadBetween(newNode, terminal);
        if (stubRoad != null) addSpawnZone(newNode, stubRoad);

        // Nâng cấp terminal → intersection nếu cần
        upgradeToIntersection(terminal);

        return newNode;
    }

    /**
     * Mở rộng thành ngã tư đầy đủ từ terminal:
     * Thêm 3 nhánh mới (tiếp tục + vuông góc trái + vuông góc phải).
     *
     * @return danh sách node mới tạo ra
     */
    public java.util.List<Node> extendAsFullIntersection(Node terminal) {
        // Tìm hướng ra duy nhất từ terminal (hướng vào mạng lưới)
        Road outgoing = null;
        for (Road r : roads) {
            if (r.getFrom() == terminal) { outgoing = r; break; }
        }
        if (outgoing == null) return Collections.emptyList();

        // Hướng "tiếp tục" = ngược chiều đường nối vào (đi xa mạng lưới)
        double cDirX = outgoing.getDirX(), cDirY = outgoing.getDirY();
        double continueAngle = Math.atan2(-cDirY, -cDirX); // FIXED: opposite = away from network

        // Vuông góc
        double perpCW  = continueAngle + Math.PI / 2;   // phải
        double perpCCW = continueAngle - Math.PI / 2;   // trái

        java.util.List<Node> created = new ArrayList<>();
        for (double angle : new double[]{continueAngle, perpCW, perpCCW}) {
            Node n = extendFromTerminal(terminal, angle);
            if (n != null) created.add(n);
        }

        upgradeToIntersection(terminal);
        return created;
    }

    /** Nâng cấp node → IntersectionController nếu có ≥ 2 đường đến. */
    /**
     * Nâng cấp node → IntersectionController chỉ khi là NGÃ TƯ THẬT
     * (có ít nhất 2 đường tiếp cận từ CÁC HƯỚNG KHÁC NHAU, không phải đường thẳng).
     *
     * Đường thẳng liên tiếp (2 đoạn cùng hướng) KHÔNG nhận đèn giao thông.
     */
    private void upgradeToIntersection(Node node) {
        // Thu thập tất cả đường có to == node
        List<Road> incoming = new ArrayList<>();
        for (Road r : roads) if (r.getTo() == node) incoming.add(r);
        if (incoming.size() < 2) return;

        // Kiểm tra có phải ngã tư thật không (có đường từ hướng vuông góc)
        boolean realIntersection = false;
        outer:
        for (int i = 0; i < incoming.size(); i++) {
            for (int j = i+1; j < incoming.size(); j++) {
                Road ri = incoming.get(i), rj = incoming.get(j);
                double dot = Math.abs(ri.getDirX()*rj.getDirX() + ri.getDirY()*rj.getDirY());
                // dot ≈ 0 = vuông góc (ngã tư thật), dot ≈ 1 = song song (đường thẳng)
                if (dot < 0.7) { realIntersection = true; break outer; }
            }
        }

        if (!realIntersection) return;   // chỉ là đường thẳng nối tiếp → không cần đèn

        if (intersections.containsKey(node)) {
            IntersectionController ic = intersections.get(node);
            for (Road r : roads) if (r.getTo() == node) ic.registerApproachRoad(r);
            ic.initPhases();
        } else {
            addIntersection(node, editorHalfWidth);
        }
    }


    /**
     * Kết nối hai terminal nodes bằng một con đường 2 chiều mới.
     * Dùng khi người dùng muốn nối 2 đoạn đường đang chỉ vào nhau (facing roads).
     */
    public Node connectTerminals(Node a, Node b) {
        // Check not already connected
        for (Road r : roads) if ((r.getFrom()==a&&r.getTo()==b)||(r.getFrom()==b&&r.getTo()==a)) return b;
        double laneWidth = editorHalfWidth * 2.0 / editorLaneCount;
        String fwd = "RC_" + a.getId() + "_" + b.getId();
        String bwd = "RC_" + b.getId() + "_" + a.getId();
        addBidirectionalRoad(fwd, bwd, a, b, laneWidth, editorLaneCount);
        removeSpawnZone(a); removeSpawnZone(b);
        upgradeToIntersection(a); upgradeToIntersection(b);
        return b;
    }

    /**
     * Đặt ngã tư linh hoạt (flex intersection) tại vị trí (cx,cy).
     * Tự động kết nối tất cả SpawnZone terminals trong vòng snapDist.
     * Không yêu cầu hướng cụ thể — hoạt động với bất kỳ góc đường nào.
     * @return số nhánh kết nối thành công
     */
    public int placeFlexIntersection(double cx, double cy, double snapDist) {
        List<Node> near = new ArrayList<>();
        for (SpawnZone sz : spawnZones) {
            if (sz.getNode().distanceTo(cx, cy) < snapDist) near.add(sz.getNode());
        }
        if (near.size() < 2) return 0;

        // Use centroid as junction center (or nearest existing node)
        double avgX = near.stream().mapToDouble(Node::getX).average().orElse(cx);
        double avgY = near.stream().mapToDouble(Node::getY).average().orElse(cy);

        // Check if a suitable existing node is already near centroid
        Node hub = null;
        for (Node n : nodes) {
            if (n.distanceTo(avgX, avgY) < snapDist * 0.5 && !near.contains(n)) { hub = n; break; }
        }
        if (hub == null) {
            hub = new Node("FJ" + nodes.size(), avgX, avgY);
            addNode(hub);
        }

        double laneWidth = editorHalfWidth * 2.0 / editorLaneCount;
        int connected = 0;
        for (Node terminal : near) {
            String fwd = "FJ_" + hub.getId() + "_" + terminal.getId();
            String bwd = "FJ_" + terminal.getId() + "_" + hub.getId();
            addBidirectionalRoad(fwd, bwd, hub, terminal, laneWidth, editorLaneCount);
            removeSpawnZone(terminal);
            connected++;
        }
        upgradeToIntersection(hub);
        return connected;
    }

    /** Kiểm tra xem đã có đường đi theo angle từ node này chưa. */
    public boolean isDirectionOccupied(Node node, double angle) {
        double cx = Math.cos(angle), cy = Math.sin(angle);
        for (Road r : roads) {
            if (r.getFrom() == node) {
                double dot = r.getDirX()*cx + r.getDirY()*cy;
                if (dot > 0.85) return true;
            }
        }
        return false;
    }

    /** Xoá node mới nhất và các đường liên quan (undo đơn giản). */
    public boolean undoLastExtension() {
        // Tìm terminal node mới nhất (có dạng "T...")
        Node lastT = null;
        for (int i = nodes.size()-1; i >= 0; i--) {
            if (nodes.get(i).getId().startsWith("T")) { lastT = nodes.get(i); break; }
        }
        if (lastT == null) return false;

        Node finalLastT = lastT;
        // Tìm node kết nối với lastT (terminal gốc)
        Node parentNode = null;
        List<Road> toRemove = new ArrayList<>();
        for (Road r : roads) {
            if (r.getFrom() == finalLastT || r.getTo() == finalLastT) {
                toRemove.add(r);
                if (r.getFrom() == finalLastT) parentNode = r.getTo();
                else                            parentNode = r.getFrom();
            }
        }

        // Xoá roads, nodes, spawnZones liên quan
        roads.removeAll(toRemove);
        for (Road r : toRemove) {
            if (r.getFrom() != null) r.getFrom().removeOutgoingRoad(r);
        }
        removeSpawnZone(finalLastT);
        nodes.remove(finalLastT);

        // Khôi phục SpawnZone cho parentNode nếu nó trở lại terminal
        final Node finalParent = parentNode;
        if (finalParent != null) {
            long incoming = roads.stream().filter(r -> r.getTo() == finalParent).count();
            if (incoming <= 1) {
                // Trở lại là terminal → xoá intersection, thêm spawn
                intersections.remove(parentNode);
                Road stub = null;
                for (Road r : roads) if (r.getFrom() == parentNode) { stub = r; break; }
                if (stub == null) for (Road r : roads) { if (r.getTo() == parentNode) { 
                    // tạo stub ngược
                    stub = findRoadBetween(parentNode, r.getFrom()); break; 
                }}
                // Thêm SpawnZone trỏ vào parentNode nếu có road vào
                Road inRoad = null;
                for (Road r : roads) if (r.getTo() == parentNode) { inRoad = r; break; }
                if (inRoad != null) {
                    // SpawnZone dùng road đi ngược ra ngoài
                    Road outRoad = findRoadBetween(parentNode, inRoad.getFrom());
                    if (outRoad != null) addSpawnZone(parentNode, outRoad);
                }
            }
        }

        // Xoá vehicles trên roads đã xoá
        vehicles.removeIf(v -> toRemove.contains(v.getCurrentRoad()));
        return true;
    }
}
