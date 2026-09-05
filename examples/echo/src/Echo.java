import com.github.pfichtner.espressomachine.api.*;

class Echo {
    public static void main(String[] args) {
        Serial.begin(9600);
        while (true) {
            if (Serial.available() > 0) {
                Serial.write(Serial.read());
            }
        }
    }
}
