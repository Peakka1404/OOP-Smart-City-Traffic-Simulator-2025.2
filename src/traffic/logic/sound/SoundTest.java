package traffic.logic.sound;

public class SoundTest {

    public static void main(String[] args) {

        EngineSound engineSound = new EngineSound();

        HornSound hornSound = new HornSound();

        SignalSound signalSound = new SignalSound();

        try {

            hornSound.carHorn();
            Thread.sleep(2000);

            hornSound.motorbikeHorn();
            Thread.sleep(2000);

            signalSound.carSignal();
            Thread.sleep(2000);

            signalSound.motorbikeSignal();
            Thread.sleep(2000);

            engineSound.carEngine();
            Thread.sleep(2000);

            engineSound.motorbikeEngine();
            Thread.sleep(2000);

            engineSound.ambulanceSiren();
            Thread.sleep(2000);

            engineSound.fireTruckSiren();
            Thread.sleep(2000);

            engineSound.policeSiren();
            Thread.sleep(5000);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}