# EspressoMachine target descriptor — ATmega328P @ 16 MHz (Arduino Uno)
MCU=atmega328p
TRIPLE=avr-unknown-unknown
F_CPU=16000000
# Busy-wait iterations per millisecond: F_CPU / cycles_per_iteration / 1000
# Inner loop body = 4 AVR cycles → F_CPU / 4 / 1000
DELAY_ITERS=4000
