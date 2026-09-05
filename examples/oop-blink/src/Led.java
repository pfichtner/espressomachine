import com.github.pfichtner.espressomachine.api.*;

class Led {
    int pin;

    Led(int pin) {
        this.pin = pin;
        GPIO.pinMode(pin, GPIO.OUTPUT);
    }

    void on() {
        GPIO.digitalWrite(pin, GPIO.HIGH);
    }

    void off() {
        GPIO.digitalWrite(pin, GPIO.LOW);
    }
}
