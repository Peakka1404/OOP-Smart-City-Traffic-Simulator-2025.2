package traffic.logic.sound;

public class SignalSound extends Sound {

    public void carSignal() {
        playSound("sounds/car_signal.wav");
    }

    public void motorbikeSignal() {
        playSound("sounds/motorbike_signal.wav");
    }
}