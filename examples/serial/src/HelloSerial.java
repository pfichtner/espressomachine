import com.github.pfichtner.espressomachine.api.*;

class HelloSerial {
    public static void main(String[] args) {
        Serial.begin(9600);
        while (true) {
            Serial.println('A');
            Serial.println("Hello, EspressoMachine!");
            Delay.delay(1000);
        }
    }
}
