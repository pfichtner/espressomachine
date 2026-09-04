import com.github.pfichtner.espressomachine.api.*;

class FunctionsExample {
    static void main() {
        // map: ADC range 0-1023 → PWM range 0-255
        int adcValue = 512;
        int pwm = Functions.map(adcValue, 0, 1023, 0, 255);
        GPIO.analogWrite(9, pwm);

        // constrain: clamp a sensor reading to valid PWM range
        int raw = 300;
        int clamped = Functions.constrain(raw, 0, 255);
        GPIO.analogWrite(10, clamped);
    }
}
