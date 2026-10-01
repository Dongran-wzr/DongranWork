# Dongran Work desktop shell

The Tauri 2 shell starts the Spring Boot JAR on `127.0.0.1`, waits for `/api/health`, and closes the child process when the desktop window exits. The Java process is intentionally local-only; it does not listen on external interfaces.

Build the backend first from the repository root:

```powershell
.\mvnw.cmd -DskipTests package
```

Install the Tauri 2 toolchain and run from `desktop/` with `cargo tauri dev` or `cargo tauri build`. The release bundle includes the backend JAR. The current prototype still uses its frontend demo data; the next integration step will replace those stores with the authenticated `/api` client.
