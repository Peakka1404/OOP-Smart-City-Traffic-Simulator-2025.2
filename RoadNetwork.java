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

    @Override public String toString() {
        return String.format("RoadNetwork[nodes=%d roads=%d intersections=%d vehicles=%d spawnZones=%d]",
                nodes.size(), roads.size(), intersections.size(), vehicles.size(), spawnZones.size());
    }
}
