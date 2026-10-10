import com.github.pfichtner.espressomachine.api.GPIO;
import com.github.pfichtner.espressomachine.api.Serial;
import com.github.pfichtner.espressomachine.api.Delay;

/**
 * Sensor Monitor Example
 * 
 * Combines multiple EspressoMachine APIs to demonstrate:
 * - GPIO.analogRead: Reading sensor values from ADC channels
 * - GPIO.analogWrite: PWM output control for LEDs
 * - Serial: Logging sensor data to console
 * - Delay: Timing control for sampling intervals
 */
public class SensorMonitor {

    public static void main(String[] args) {
        // Initialize serial communication for logging
        Serial.begin(9600);

        // Set up pin 13 as PWM output (built-in LED)
        GPIO.pinMode(13, GPIO.OUTPUT);

        Serial.println("=== Sensor Monitor Started ===");

        // Main measurement loop
        while (true) {
            // Read analog sensor value from channel A0
            int sensorValue = GPIO.analogRead(0);

            // Convert to voltage (0-5V range mapped from 0-1023 ADC)
            float voltage = ((float) sensorValue) * 5.0f / 1023.0f;

            // Control PWM output based on sensor reading
            // Map 0-5V to 0-255 PWM duty cycle
            int pwmValue = (int) (voltage * 255.0f / 5.0f);

            // Write PWM value to pin 13
            GPIO.analogWrite(13, pwmValue);

            // Log to serial monitor - construct log message with all values
            Serial.print("Sensor:");
            Serial.print(sensorValue);
            Serial.print(",V:");
            Serial.print((int) voltage);
            Serial.print(",PWM:");
            Serial.println(pwmValue);

            // Delay between samples (1 second)
            Delay.delay(1000);
        }
    }
}