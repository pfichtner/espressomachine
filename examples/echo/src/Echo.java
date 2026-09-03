import espressomachine.api.*;

class Echo {
    static void main() {
        Serial.begin(9600);
        while (true) {
            if (Serial.available() > 0) {
                Serial.write(Serial.read());
            }
        }
    }
}
