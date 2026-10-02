"""Real Windows AppContainer integration checks; no external network requests."""
import json, os, pathlib, shutil, socket, subprocess, sys, tempfile, time

ROOT = pathlib.Path(__file__).resolve().parents[1]
HELPER = ROOT / "target" / "debug" / "dongran-sandbox.exe"
FIXTURE = ROOT / "target" / "debug" / "examples" / "probe_child.exe"

def run(workspace, argv, timeout=10, cancel=None, eof=False):
    process = subprocess.Popen(
        [str(HELPER), "run"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8",
        env={**os.environ, "DONGRAN_PRIVATE_SENTINEL": "do-not-inherit"})
    request = dict(protocolVersion=1, executionId="native-test",
                   workspace=str(workspace), argv=list(map(str, argv)),
                   timeoutSeconds=timeout, memoryMb=512, maxProcesses=32,
                   network="deny")
    process.stdin.write(json.dumps(request) + "\n")
    process.stdin.flush()
    events = []
    for line in process.stdout:
        value = json.loads(line)
        events.append(value)
        if value["type"] == "started":
            if cancel:
                process.stdin.write('{"type":"cancel"}\n')
                process.stdin.flush()
            if eof:
                process.stdin.close()
    process.wait(timeout=15)
    errors = process.stderr.read()
    assert not errors, errors
    assert events[-1]["type"] == "exit", events
    return events

def main():
    assert sys.platform == "win32", "This integration verifier targets Windows."
    probe = json.loads(subprocess.check_output([str(HELPER), "probe"], text=True))
    assert probe["available"] and probe["networkIsolation"], probe
    with tempfile.TemporaryDirectory(prefix="dongran-sandbox-boundary-") as directory:
        root = pathlib.Path(directory)
        workspace = root / "workspace"
        workspace.mkdir()
        executable = workspace / "probe_child.exe"
        shutil.copyfile(FIXTURE, executable)
        (workspace / "input.txt").write_text("workspace-readable")
        outside = root / "private-secret.txt"
        outside.write_text("private-sentinel")
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen()
            address = "127.0.0.1:" + str(server.getsockname()[1])
            # Control: the host can reach the listener, the isolated payload cannot.
            with socket.create_connection(server.getsockname(), timeout=1):
                connection, _ = server.accept()
                connection.close()
            events = run(workspace, [executable, "boundaries", outside, address])
        assert events[-1]["status"] == "completed", events
        assert any("BOUNDARIES_OK" in e.get("text", "") for e in events), events
        assert outside.read_text() == "private-sentinel"
        assert (workspace / "output.txt").read_text() == "workspace-written"

        for mode in ("completed", "timed_out"):
            marker = workspace / (mode + "-escaped.txt")
            argv = [executable, "background", marker]
            if mode == "timed_out":
                argv.append("wait")
            events = run(workspace, argv, timeout=1 if mode == "timed_out" else 10)
            assert events[-1]["status"] == mode, events
            pid = next(int(e["text"].split("CHILD_PID=")[1].split()[0])
                       for e in events if "CHILD_PID=" in e.get("text", ""))
            import ctypes
            handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
            if handle:
                code = ctypes.c_ulong()
                ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
                ctypes.windll.kernel32.CloseHandle(handle)
                assert code.value != 259, "A child remained alive after helper exit"
            time.sleep(3.2)
            assert not marker.exists(), "A descendant survived the job"

        assert run(workspace, [executable, "sleep"], cancel=True)[-1]["status"] == "cancelled"
        assert run(workspace, [executable, "sleep"], eof=True)[-1]["status"] == "cancelled"
        assert run(workspace, [executable, "flood"])[-1]["status"] == "failed"
    print("PASS: AppContainer workspace access, private-file denial, network denial, secret-free environment, descendant cleanup, timeout, cancel/EOF and output cap.")

if __name__ == "__main__":
    main()
