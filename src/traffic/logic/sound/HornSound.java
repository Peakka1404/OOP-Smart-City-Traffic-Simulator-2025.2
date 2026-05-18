package traffic.logic.sound;

public class HornSound extends Sound {

    public void carHorn() {
        playSound("sounds/car_horn.wav");
    }

    public void motorbikeHorn() {
        playSound("sounds/motorbike_horn.wav");
    }
}