package tinyjava.cli;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import tinyjava.IrDumper;

/**
 * TinyJava CLI entry point.
 *
 * Subcommands:
 *   tinyjava build     [--target <mcu>] [--cp <dirs>] [--output <dir>] <Foo.class|dir> [Name]
 *   tinyjava inspect   [--cp <dirs>]                                   <Foo.class|dir> [Name]
 *   tinyjava emit-llvm [--cp <dirs>] [-o <out.ll>]                    <Foo.class|dir> [Name]
 *   tinyjava flash     --port <dev>                                    <Foo.hex>
 *
 * Input resolution:
 *   Foo.class          → classpath = parent dir, entry class = "Foo"
 *   dir/ Foo           → classpath = dir/, entry class = "Foo"
 *   dir1:dir2 Foo      → classpath = "dir1:dir2", entry class = "Foo"
 */
public class TinyJavaCli {

    public static void main(String[] args) throws Exception {
        // Backward-compatible: if no subcommand detected, delegate to IrDumper.
        if (args.length == 0 || isLegacyInvocation(args)) {
            IrDumper.main(args);
            return;
        }

        String subcommand = args[0];
        String[] rest = drop(args, 1);

        switch (subcommand) {
            case "build"     -> build(rest);
            case "inspect"   -> inspect(rest);
            case "emit-llvm" -> emitLlvm(rest);
            case "flash"     -> flash(rest);
            default -> {
                // Could be legacy IrDumper invocation where first arg is a classpath
                IrDumper.main(args);
            }
        }
    }

    // ------------------------------------------------------------------
    // Subcommands
    // ------------------------------------------------------------------

    static void build(String[] args) throws Exception {
        Opts o = Opts.parse(args);
        o.requireInput();
        Path outputDir = Paths.get(o.outputDir != null ? o.outputDir : "build");

        String target = o.target != null ? o.target : "atmega328p";
        Path tinyjavaHome = findHome();

        System.out.println("=== TinyJava build ===");
        System.out.println("Entry:  " + o.entryClass);
        System.out.println("Target: " + target);
        System.out.println("Output: " + outputDir.toAbsolutePath());
        System.out.println();

        // Step 1: TeaVM → LLVM IR
        Files.createDirectories(outputDir);
        Path llFile = outputDir.resolve(o.entryClass + ".ll");
        System.out.println("[1/6] TeaVM → LLVM IR ...");
        IrDumper.compile(o.classpath, o.entryClass, llFile.toString(), false);

        // Steps 2-6: LLVM IR → ELF → HEX
        new Pipeline(tinyjavaHome, target, outputDir)
                .compileToAvr(llFile, o.entryClass);
    }

    static void inspect(String[] args) throws Exception {
        Opts o = Opts.parse(args);
        o.requireInput();
        // verbose=true: prints the full TeaVM IR dump to stdout
        IrDumper.compile(o.classpath, o.entryClass, null, true);
    }

    static void emitLlvm(String[] args) throws Exception {
        Opts o = Opts.parse(args);
        o.requireInput();
        String outPath = o.outputFile;
        if (outPath == null) {
            Path dir = o.outputDir != null ? Paths.get(o.outputDir) : Paths.get(".");
            Files.createDirectories(dir);
            outPath = dir.resolve(o.entryClass + ".ll").toString();
        }
        System.out.println("emit-llvm: " + o.entryClass + " → " + outPath);
        IrDumper.compile(o.classpath, o.entryClass, outPath, false);
        System.out.println("Written: " + outPath);
    }

    static void flash(String[] args) throws Exception {
        Opts o = Opts.parse(args);
        if (o.positionals.isEmpty()) {
            die("flash: missing HEX file argument");
        }
        if (o.port == null) {
            die("flash: --port <device> is required");
        }
        String hexFile = o.positionals.get(0);
        System.out.println("Flashing " + hexFile + " to " + o.port + " ...");
        new ProcessBuilder("avrdude",
                "-c", "arduino",
                "-p", "atmega328p",
                "-P", o.port,
                "-U", "flash:w:" + hexFile + ":i")
                .inheritIO()
                .start()
                .waitFor();
    }

    // ------------------------------------------------------------------
    // Argument parsing
    // ------------------------------------------------------------------

    static class Opts {
        String target;
        String classpath;    // colon-separated; derived from positional if needed
        String entryClass;
        String outputDir;    // --output
        String outputFile;   // -o (for emit-llvm)
        String port;         // --port (for flash)
        List<String> positionals = new ArrayList<>();
        List<String> extraCp = new ArrayList<>();

        static Opts parse(String[] args) {
            Opts o = new Opts();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--target"  -> o.target     = args[++i];
                    case "--output"  -> o.outputDir  = args[++i];
                    case "--cp"      -> o.extraCp.add(args[++i]);
                    case "-o"        -> o.outputFile = args[++i];
                    case "--port"    -> o.port       = args[++i];
                    case "--main"    -> o.entryClass = args[++i];
                    default -> {
                        if (args[i].startsWith("--")) die("Unknown flag: " + args[i]);
                        o.positionals.add(args[i]);
                    }
                }
            }
            return o;
        }

        void requireInput() {
            if (positionals.isEmpty()) die("Missing input: <Foo.class|classdir> [ClassName]");
            resolveInput();
        }

        private void resolveInput() {
            if (!extraCp.isEmpty()) {
                // --cp was given: classpath is fully specified; positional[0] is the entry class.
                classpath = String.join(":", extraCp);
                if (entryClass == null) {
                    if (positionals.isEmpty()) die("Missing entry class name after --cp.");
                    entryClass = positionals.get(0);
                }
                return;
            }

            String first = positionals.get(0);

            if (first.endsWith(".class")) {
                // Foo.class → classpath = parent dir, entry = "Foo"
                File f;
                try { f = new File(first).getCanonicalFile(); }
                catch (IOException e) { f = new File(first).getAbsoluteFile(); }
                String baseCp = f.getParent();
                if (entryClass == null) {
                    String name = f.getName();
                    entryClass = name.substring(0, name.length() - ".class".length());
                }
                classpath = baseCp;
            } else {
                // dir [ClassName]  OR  dir1:dir2 [ClassName]
                classpath = first;
                if (entryClass == null && positionals.size() >= 2) {
                    entryClass = positionals.get(1);
                }
                if (entryClass == null) {
                    die("Missing entry class name. Usage: tinyjava <cmd> <dir> <ClassName>");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isLegacyInvocation(String[] args) {
        // Heuristic: if the first arg looks like a file/directory path (not a keyword),
        // treat as legacy IrDumper(classpath, entryClass, [output.ll]) invocation.
        if (args.length < 2) return false;
        String first = args[0];
        return !first.equals("build") && !first.equals("inspect")
                && !first.equals("emit-llvm") && !first.equals("flash");
    }

    static Path findHome() {
        try {
            // Resolve TINYJAVA_HOME from the location of this JAR.
            // JAR lives at: $TINYJAVA_HOME/teavm-backend/llvm/target/tinyjava.jar
            Path jarPath = Paths.get(
                    TinyJavaCli.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            return jarPath.getParent()   // target/
                          .getParent()   // llvm/
                          .getParent()   // teavm-backend/
                          .getParent();  // tinyjava/
        } catch (URISyntaxException e) {
            throw new RuntimeException("Cannot resolve TINYJAVA_HOME", e);
        }
    }

    private static String[] drop(String[] arr, int n) {
        String[] r = new String[arr.length - n];
        System.arraycopy(arr, n, r, 0, r.length);
        return r;
    }

    static void die(String msg) {
        System.err.println("Error: " + msg);
        System.err.println();
        System.err.println("Usage:");
        System.err.println("  tinyjava build     [--target <mcu>] [--cp <dirs>] [--output <dir>] <Foo.class|dir> [Name]");
        System.err.println("  tinyjava inspect   [--cp <dirs>]                                   <Foo.class|dir> [Name]");
        System.err.println("  tinyjava emit-llvm [--cp <dirs>] [-o <out.ll>]                    <Foo.class|dir> [Name]");
        System.err.println("  tinyjava flash     --port <dev>                                    <Foo.hex>");
        System.exit(1);
        throw new RuntimeException("unreachable");
    }
}
