/* TinyJava ATmega328P linker script
 * CPU: ATmega328P
 * Flash: 32 KB at 0x000000
 * SRAM:   2 KB at 0x800100 (AVR data address space)
 */

OUTPUT_FORMAT("elf32-avr")
OUTPUT_ARCH(avr:5)

MEMORY
{
    flash (rx)  : ORIGIN = 0x000000, LENGTH = 32K
    sram  (rw!x): ORIGIN = 0x800100, LENGTH = 2K
}

SECTIONS
{
    /* Reset and interrupt vectors must be at flash origin */
    .vectors 0x000000 :
    {
        KEEP(*(.vectors))
    } > flash

    /* Program text */
    .text :
    {
        *(.text*)
        *(.rodata*)
    } > flash

    /* Initialized data — load from flash, run from SRAM */
    .data :
    {
        _data_start = .;
        *(.data*)
        _data_end = .;
    } > sram AT > flash

    /* Zero-initialized data */
    .bss :
    {
        _bss_start = .;
        *(.bss*)
        *(COMMON)
        _bss_end = .;
    } > sram

    /* Stack grows downward from top of SRAM */
    _stack_top = ORIGIN(sram) + LENGTH(sram) - 1;
}
