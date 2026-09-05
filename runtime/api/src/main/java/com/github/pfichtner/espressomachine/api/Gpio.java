package com.github.pfichtner.espressomachine.api;

/**
 * Mockable OOP wrapper around the static {@link GPIO} API.
 *
 * In production each method delegates to the corresponding {@link GPIO} intrinsic,
 * which the EspressoMachine backend lowers to AVR memory-mapped I/O.
 * In host-side unit tests the instance can be replaced with a mock or stub.
 */
public class Gpio {
    public void pinMode(int pin, int mode)      { GPIO.pinMode(pin, mode); }
    public void digitalWrite(int pin, int value) { GPIO.digitalWrite(pin, value); }
    public int  digitalRead(int pin)             { return GPIO.digitalRead(pin); }
    public int  analogRead(int pin)              { return GPIO.analogRead(pin); }
    public void analogWrite(int pin, int value)  { GPIO.analogWrite(pin, value); }
}
