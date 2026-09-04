# ---- Stage 1: Build the compiler JAR ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn package -q -f teavm-backend/llvm/pom.xml

# ---- Stage 2: Runtime environment ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /workspace

# Install LLVM 18 and AVR toolchain (needed for the 'build' and 'flash' subcommands).
# llvm-18 is not in the base jammy repos, so add LLVM's official apt repository.
RUN apt-get update -y && \
    apt-get install -y --no-install-recommends wget gnupg ca-certificates && \
    wget -qO- https://apt.llvm.org/llvm-snapshot.gpg.key | gpg --dearmor -o /usr/share/keyrings/apt-llvm-org.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/apt-llvm-org.gpg] http://apt.llvm.org/jammy/ llvm-toolchain-jammy-18 main" > /etc/apt/sources.list.d/llvm-18.list && \
    apt-get update -y && \
    apt-get install -y --no-install-recommends llvm-18 binutils-avr avrdude && \
    rm -rf /var/lib/apt/lists/*

# Copy essential project files from the build stage
COPY --from=build /workspace/teavm-backend/llvm/target/*.jar /workspace/teavm-backend/llvm/target/
COPY --from=build /workspace/runtime /workspace/runtime
COPY --from=build /workspace/targets /workspace/targets

# Copy CLI launcher and make it executable
COPY bin/espressomachine /workspace/bin/espressomachine
RUN chmod +x /workspace/bin/espressomachine

ENTRYPOINT ["/workspace/bin/espressomachine"]
