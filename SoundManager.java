import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;

/**
 * SoundManager — Quản lý âm thanh.
 * Kết hợp tổng hợp procedural (sine/square) và phát file âm thanh (wav).
 * Tất cả phát âm thanh đều bất đồng bộ (không block luồng chính).
 */
public class SoundManager {

    private final Map<String, Clip> vehicleClips = new HashMap<>();
    private static final int   SAMPLE_RATE = 22050;
    private static final float MASTER_VOL  = 0.55f;

    private boolean enabled = true;

    // Thread pool daemon để phát sound không block UI
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sound-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    // Debounce: tránh cùng loại sound bị lặp quá nhiều lần
    private final Map<String, Long> lastPlayed = new ConcurrentHashMap<>();
    private static final Map<String, Long> DEBOUNCE_MS = new HashMap<>() {{
        put("engine",  300L);
        put("horn",    500L);
        put("barrier", 250L);
        put("collide", 200L);
        put("brake",   300L);
        put("signal",  400L);
        put("arrive",  600L);
    }};

    // ── Dạng sóng ────────────────────────────────────────────────────────
    private enum Wave { SINE, SQUARE, SAW, TRIANGLE }

    // ─────────────────────────────────────────────────────────────────────
    //  Constructor & Load
    // ─────────────────────────────────────────────────────────────────────
    public SoundManager() {
        loadVehicleSounds();
    }

    private void loadVehicleSounds() {
        Map<String, String> files = new HashMap<>() {{
            put("Car",       "resources/Sound/CarSound.wav");
            put("Ambulance", "resources/Sound/AmbulanceSound.wav");
            put("FireTruck", "resources/Sound/FireTruckSound.wav");
            put("Motorbike", "resources/Sound/MotorbikeSound.wav");
        }};

        for (Map.Entry<String, String> e : files.entrySet()) {
            try {
                AudioInputStream ais = AudioSystem.getAudioInputStream(new File(e.getValue()));
                Clip clip = AudioSystem.getClip();
                clip.open(ais);
                vehicleClips.put(e.getKey(), clip);
                System.out.println("[SoundManager] Loaded: " + e.getValue());
            } catch (Exception ex) {
                System.err.println("[SoundManager] Không load được: " + e.getValue());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────

    /** Phát âm thanh riêng của từng loại xe */
    public void playVehicleSound(String type) {
        if (!enabled) return;
        Clip clip = vehicleClips.getOrDefault(type, vehicleClips.get("Car"));
        if (clip == null) { playSignal(); return; }
        pool.submit(() -> {
            try {
                clip.stop();
                clip.setFramePosition(0);
                clip.start();
            } catch (Exception ignored) {}
        });
    }

    /** Tiếng động cơ — pitch tăng theo speed (0‥1 = tỉ lệ tốc độ). */
    public void playEngine(double speedRatio) {
        if (!canPlay("engine")) return;
        double baseFreq = 60 + speedRatio * 120;   // 60 Hz idle → 180 Hz full speed
        pool.submit(() -> {
            byte[] buf = mix(
                generateTone(baseFreq,      180, 0.35f, Wave.SAW),
                generateTone(baseFreq * 2,  180, 0.15f, Wave.SINE)
            );
            playBuffer(buf);
        });
    }

    /** Bấm còi. */
    public void playHorn() {
        if (!canPlay("horn")) return;
        pool.submit(() -> {
            byte[] a = generateTone(440, 120, 0.5f, Wave.SQUARE);
            byte[] b = generateTone(550, 120, 0.5f, Wave.SQUARE);
            playBuffer(concat(concat(a, silence(20)), b));
        });
    }

    /** Va chạm vào lề đường. */
    public void playBarrierHit() {
        if (!canPlay("barrier")) return;
        pool.submit(() -> {
            byte[] thud   = generateTone(80,  80, 0.6f, Wave.SINE);
            byte[] noise  = generateNoise(40, 0.3f);
            playBuffer(concat(noise, thud));
        });
    }

    /** Va chạm xe–xe. */
    public void playCollision() {
        if (!canPlay("collide")) return;
        pool.submit(() -> {
            byte[] bump = mix(
                generateTone(120, 100, 0.5f, Wave.SINE),
                generateNoise(60,  0.4f)
            );
            playBuffer(bump);
        });
    }

    /** Phanh gấp. */
    public void playBrake() {
        if (!canPlay("brake")) return;
        pool.submit(() -> playBuffer(generateSweep(900, 300, 150, 0.35f)));
    }

    /** Tick đèn xi-nhan. */
    public void playSignal() {
        if (!canPlay("signal")) return;
        pool.submit(() -> {
            byte[] tick = generateTone(1000, 55, 0.25f, Wave.SQUARE);
            playBuffer(tick);
        });
    }

    /** Chime khi xe đến đích. */
    public void playArrive() {
        if (!canPlay("arrive")) return;
        pool.submit(() -> {
            double[] notes = {523.25, 659.25, 783.99}; // C5 E5 G5
            List<byte[]> parts = new ArrayList<>();
            for (double f : notes) {
                parts.add(generateTone(f, 100, 0.4f, Wave.SINE));
                parts.add(silence(20));
            }
            byte[] chime = parts.stream().reduce(new byte[0], SoundManager::concat);
            playBuffer(chime);
        });
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled()              { return enabled; }

    public void shutdown() { pool.shutdownNow(); }

    // ─────────────────────────────────────────────────────────────────────
    //  Debounce helper
    // ─────────────────────────────────────────────────────────────────────

    private boolean canPlay(String key) {
        if (!enabled) return false;
        long now  = System.currentTimeMillis();
        long wait = DEBOUNCE_MS.getOrDefault(key, 300L);
        Long last = lastPlayed.get(key);
        if (last == null || now - last > wait) {
            lastPlayed.put(key, now);
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phát buffer qua SourceDataLine
    // ─────────────────────────────────────────────────────────────────────

    private void playBuffer(byte[] buf) {
        try {
            AudioFormat fmt  = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) return;
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            line.start();
            line.write(buf, 0, buf.length);
            line.drain();
            line.close();
        } catch (Exception ignored) { /* audio unavailable */ }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Tổng hợp sóng âm
    // ─────────────────────────────────────────────────────────────────────

    private byte[] generateTone(double freq, int durationMs, float gain, Wave wave) {
        int n   = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t   = (double) i / SAMPLE_RATE;
            double val = switch (wave) {
                case SINE     -> Math.sin(2 * Math.PI * freq * t);
                case SQUARE   -> Math.signum(Math.sin(2 * Math.PI * freq * t));
                case SAW      -> 2 * (t * freq - Math.floor(t * freq + 0.5));
                case TRIANGLE -> 2 * Math.abs(2 * (t * freq - Math.floor(t * freq + 0.5))) - 1;
            };
            val *= envelope(i, n) * gain * MASTER_VOL;
            short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, val * Short.MAX_VALUE));
            buf[i * 2]     = (byte) (s & 0xff);
            buf[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return buf;
    }

    private byte[] generateSweep(double f1, double f2, int durationMs, float gain) {
        int n   = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[n * 2];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double progress = (double) i / n;
            double freq     = f1 + (f2 - f1) * progress;
            phase += 2 * Math.PI * freq / SAMPLE_RATE;
            double val = Math.sin(phase) * envelope(i, n) * gain * MASTER_VOL;
            short s = (short)(val * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (s & 0xff);
            buf[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return buf;
    }

    private byte[] generateNoise(int durationMs, float gain) {
        int n   = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[n * 2];
        Random rng  = new Random();
        for (int i = 0; i < n; i++) {
            double val = (rng.nextDouble() * 2 - 1) * envelope(i, n) * gain * MASTER_VOL;
            short s = (short)(val * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (s & 0xff);
            buf[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return buf;
    }

    private static byte[] silence(int ms) {
        return new byte[22050 * ms / 1000 * 2];
    }

    private double envelope(int i, int total) {
        int atk = Math.min(total / 10, 220);
        int rel = Math.min(total / 10, 220);
        if (i < atk)           return (double) i / atk;
        if (i > total - rel)   return (double)(total - i) / rel;
        return 1.0;
    }

    private static byte[] mix(byte[] a, byte[] b) {
        int len = Math.max(a.length, b.length);
        byte[] out = new byte[len];
        for (int i = 0; i < len; i += 2) {
            short sa = (i < a.length - 1) ? (short)((a[i+1]<<8)|( a[i]&0xff)) : 0;
            short sb = (i < b.length - 1) ? (short)((b[i+1]<<8)|( b[i]&0xff)) : 0;
            short sm = (short)((sa + sb) / 2);
            out[i]   = (byte)(sm & 0xff);
            if (i+1 < len) out[i+1] = (byte)((sm>>8) & 0xff);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}