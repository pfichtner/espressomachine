package com.github.pfichtner.espressomachine.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Orchestrates the EspressoMachine compilation pipeline from LLVM IR to a flashable HEX file.
 *
 * Step 1 (TeaVM → LLVM IR) is handled by IrDumper.compile() before this class is invoked.
 * Steps 2-6 here shell out to the AVR toolchain.
 */
class Pipeline {

    private final Path espressomachineHome;
    private final String target;   // e.g. "atmega328p"
    private final Path outputDir;

    Pipeline(Path espressomachineHome, String target, Path outputDir) {
        this.espressomachineHome = espressomachineHome;
        this.target = target;
        this.outputDir = outputDir;
    }

    // ------------------------------------------------------------------
    // Entry: compile LLVM IR → ELF → HEX
    // ------------------------------------------------------------------

    void compileToAvr(Path inputLl, String entryClass) throws IOException, InterruptedException {
        Files.createDirectories(outputDir);

        Path targetDir = espressomachineHome.resolve("runtime/avr/" + target);
        Path scriptsDir = espressomachineHome.resolve("targets/" + target);

        // Load target descriptor
        String mcu = readTargetVar(targetDir, "MCU", "atmega328p");
        String delayIters = readTargetVar(targetDir, "DELAY_ITERS", "4000");

        // Detect feature usage by scanning the generated LLVM IR.
        String generatedIr = Files.readString(inputLl);
        boolean usesSerial = generatedIr.contains("@__espressomachine_serial_write");
        boolean usesMillis = generatedIr.contains("@__espressomachine_time_millis");

        System.out.println("[2/6] Assembling startup.S ...");
        Path startupS = substituteStartup(scriptsDir, entryClass);
        run("avr-as", "-mmcu=" + mcu, startupS.toString(), "-o",
                outputDir.resolve("startup.o").toString());

        System.out.println("[3/6] Compiling " + entryClass + ".ll → .o ...");
        run("llc-18", "-march=avr", "-mcpu=" + mcu, "-filetype=obj",
                "-function-sections", "-data-sections",
                "-o", outputDir.resolve(entryClass + ".o").toString(),
                inputLl.toString());

        System.out.println("[4/6] Compiling gpio.ll → gpio.o ...");
        run("llc-18", "-march=avr", "-mcpu=" + mcu, "-filetype=obj",
                "-function-sections", "-data-sections",
                "-o", outputDir.resolve("gpio.o").toString(),
                targetDir.resolve("gpio.ll").toString());

        System.out.println("[5/6] Generating delay.ll (DELAY_ITERS=" + delayIters + ") ...");
        Path calibratedDelay = substituteDelay(targetDir, delayIters);
        run("llc-18", "-march=avr", "-mcpu=" + mcu, "-filetype=obj",
                "-function-sections", "-data-sections",
                "-o", outputDir.resolve("delay.o").toString(),
                calibratedDelay.toString());

        if (usesSerial) {
            System.out.println("[5b/6] Compiling serial.ll → serial.o ...");
            run("llc-18", "-march=avr", "-mcpu=" + mcu, "-filetype=obj",
                    "-function-sections", "-data-sections",
                    "-o", outputDir.resolve("serial.o").toString(),
                    targetDir.resolve("serial.ll").toString());
        }

        if (usesMillis) {
            System.out.println("[5c/6] Compiling time.ll → time.o ...");
            run("llc-18", "-march=avr", "-mcpu=" + mcu, "-filetype=obj",
                    "-function-sections", "-data-sections",
                    "-o", outputDir.resolve("time.o").toString(),
                    targetDir.resolve("time.ll").toString());
        }

        System.out.println("[6/6] Linking → " + entryClass + ".elf ...");
        List<String> linkArgs = new ArrayList<>(Arrays.asList(
                "avr-ld",
                "--gc-sections",
                "-T", scriptsDir.resolve("linker.ld").toString(),
                outputDir.resolve("startup.o").toString(),
                outputDir.resolve(entryClass + ".o").toString(),
                outputDir.resolve("gpio.o").toString(),
                outputDir.resolve("delay.o").toString()));
        if (usesSerial) linkArgs.add(outputDir.resolve("serial.o").toString());
        if (usesMillis) linkArgs.add(outputDir.resolve("time.o").toString());
        linkArgs.add("-o");
        linkArgs.add(outputDir.resolve(entryClass + ".elf").toString());
        run(linkArgs.toArray(new String[0]));

        run("avr-objcopy", "-O", "ihex", "-R", ".eeprom",
                outputDir.resolve(entryClass + ".elf").toString(),
                outputDir.resolve(entryClass + ".hex").toString());

        System.out.println();
        run("avr-size", outputDir.resolve(entryClass + ".elf").toString());
        System.out.println("Flash: " + outputDir.resolve(entryClass + ".hex").toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Path substituteStartup(Path scriptsDir, String entryClass) throws IOException {
        String template = Files.readString(scriptsDir.resolve("startup.S"));
        String filled = template.replace("__ENTRY_CLASS__", entryClass);
        Path out = outputDir.resolve("startup_" + entryClass + ".S");
        Files.writeString(out, filled);
        return out;
    }

    private Path substituteDelay(Path targetDir, String delayIters) throws IOException {
        String template = Files.readString(targetDir.resolve("delay.ll"));
        String filled = template.replace("__DELAY_ITERS__", delayIters);
        Path out = outputDir.resolve("delay_" + delayIters + ".ll");
        Files.writeString(out, filled);
        return out;
    }

    private String readTargetVar(Path targetDir, String varName, String defaultValue)
            throws IOException {
        Path targetSh = targetDir.resolve("target.sh");
        if (!Files.exists(targetSh)) return defaultValue;
        for (String line : Files.readAllLines(targetSh)) {
            line = line.strip();
            if (line.startsWith(varName + "=")) {
                return line.substring(varName.length() + 1).replaceAll("#.*", "").strip();
            }
        }
        return defaultValue;
    }

    private void run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) {
            throw new IOException("Command failed (exit " + code + "): " + String.join(" ", cmd));
        }
    }
}
