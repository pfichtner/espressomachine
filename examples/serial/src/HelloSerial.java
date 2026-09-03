import bytelight.api.*;

class HelloSerial {
    static void main() {
        Serial.begin(9600);
        while (true) {
            Serial.println('A');
            Serial.println("Hello, ByteLight!");
            Delay.ms(1000);
        }
    }
}
