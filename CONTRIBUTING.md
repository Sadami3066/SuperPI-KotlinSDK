# Contributing to SuperPI-KotlinSDK

Thank you for contributing to SuperPI-KotlinSDK, the Kotlin Multiplatform agent SDK for Android and JVM applications.

## Development Workflow

1. **Requirements**: JDK 17+ and Android SDK (`compileSdk 36`, `minSdk 26`).
2. **Build & Test**:
   ```bash
   ./scripts/gw.sh :pi-ai:jvmTest :pi-agent:jvmTest :pi-mcp:jvmTest :pi-coding-agent:jvmTest
   ./scripts/gw.sh check
   ```
   Use `./scripts/gw.sh` on memory-constrained machines to serialize Gradle runs.
3. **Architecture**:
   - Maintain strict dependency direction: `pi-coding-agent` -> `pi-agent` -> `pi-ai` and `pi-coding-agent` -> `pi-mcp` -> `pi-ai`.
   - Keep JSONL session files and `auth.json` / `models.json` / `mcp.json` formats compatible with upstream `pi`.
4. **License**: Contributions are licensed under the [MIT License](LICENSE).
