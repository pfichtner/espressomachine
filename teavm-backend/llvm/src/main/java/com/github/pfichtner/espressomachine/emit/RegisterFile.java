package com.github.pfichtner.espressomachine.emit;

/**
 * ATmega328P register files used by the intrinsic emitters.
 *
 * Addresses are the memory-mapped (IO-space) addresses written via {@code inttoptr}.
 */
public enum RegisterFile {

    // ---- GPIO data / direction registers ----
    DDRB(0x24), PORTB(0x25), PINB(0x23),
    DDRC(0x27), PORTC(0x28),
    DDRD(0x2A), PORTD(0x2B), PIND(0x29),

    // ---- UART (USART0) ----
    UCSR0A(192),  // 0xC0  status register (RXC0 = receives data)
    UCSR0B(193),  // 0xC1  control B (RXEN | TXEN)
    UCSR0C(194),  // 0xC2  control C (8N1 frame)
    UBRR0H(197),  // 0xC5  baud rate register high
    UBRR0L(196),  // 0xC4  baud rate register low
    UDR0(198);    // 0xC6  data register

    private final int address;

    RegisterFile(int address) {
        this.address = address;
    }

    public int address() {
        return address;
    }
}
