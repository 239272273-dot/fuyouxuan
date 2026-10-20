import ctypes
import ctypes.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
FAKE_COMMAND = r'''
import json
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
command = Path(sys.argv[0]).name
scenario = os.environ["TEST_SCENARIO"]
root = Path(os.environ["TEST_ROOT"])
payload = sys.stdin.read() if "exec" in arguments else ""
with (root / "commands.jsonl").open("a") as output:
    output.write(json.dumps([command, arguments, payload]) + "\n")
if command == "wrk":
    (root / "load-finished").touch()
    if scenario == "wrk-failed":
        print("Independent token pool exhausted")
        sys.exit(2)
    print("10 requests in 1.25s, 1KB read\nRequests/sec: 8.00")
    print("WRK_SUMMARY requests=10 elapsed_seconds=1.250000 http_errors=0 connect_errors=0 read_errors=0 write_errors=0 timeouts=0")
elif arguments[0] == "info":
    sys.exit(1 if scenario == "unavailable" else 0)
elif arguments[:2] == ["compose", "up"]:
    sys.exit(3 if scenario == "up-failed" else 0)
elif "mysql" in arguments:
    query = arguments[-1]
    if query.startswith("SELECT COUNT(*)"):
        print(10 if (root / "load-finished").exists() else 0)
    elif query.startswith("SELECT stock"):
        print(1 if scenario == "stock-mismatch" else 0)
    elif query.startswith("SELECT id"):
        print("\n".join(str(order_id) for order_id in range(1, 11)))
elif "redis-cli" in arguments:
    if "LLEN" in arguments:
        print(1 if scenario == "pending" else 0)
    elif "HLEN" in arguments:
        print(10)
    elif "GET" in arguments:
        print(0)
    else:
        print("OK")
elif "consumerProgress" in arguments:
    print("Diff Total: 0")
'''


class CommandLineTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fuyouxuan-cli-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(REPOSITORY_ROOT / "scripts", self.root / "scripts")
        (self.root / ".env").write_text("HTTP_PORT=8080\r\n")
        mock_bin = self.root / "bin"
        mock_bin.mkdir()
        for command in ("docker", "wrk"):
            executable = mock_bin / command
            executable.write_text("#!" + sys.executable + "\n" + FAKE_COMMAND)
            executable.chmod(0o755)
        self.environment = dict(os.environ, PATH=str(mock_bin) + os.pathsep + os.environ["PATH"],
                                TEST_ROOT=str(self.root), WRK_THREADS="2", WRK_CONNECTIONS="4",
                                WRK_USERS_PER_THREAD="5")

    def run_script(self, script, scenario="success"):
        environment = dict(self.environment, TEST_SCENARIO=scenario)
        return subprocess.run(["bash", str(self.root / "scripts" / script)], env=environment,
                              stdin=subprocess.DEVNULL, text=True, capture_output=True, timeout=30)

    def commands(self):
        log = self.root / "commands.jsonl"
        return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    def test_unavailable_engine_does_not_start_or_stop_containers(self):
        for script in ("docker-up.sh", "docker-down.sh"):
            result = self.run_script(script, "unavailable")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("not reachable", result.stderr)
        self.assertTrue(all(arguments == ["info"] for _, arguments, _ in self.commands()))

    def test_failed_compose_does_not_report_ready(self):
        result = self.run_script("docker-up.sh", "up-failed")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("FuYouXuan is ready", result.stdout)

    def test_benchmark_reports_admission_rate_and_checks_both_stocks(self):
        result = self.run_script("benchmark-seckill.sh")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("accepted_per_sec=8.00 elapsed_seconds=1.250000", result.stdout)
        self.assertIn("mysql_orders=10 redis_stock=0 mysql_stock=0", result.stdout)
        self.assertIn("Cleaning benchmark-only", result.stdout)
        self.assertNotIn("business_tps=", result.stdout)
        self.assertFalse(any("seckill:order:queue:" in " ".join(arguments) and "DEL" in arguments
                             for _, arguments, _ in self.commands()))

    def test_pending_orders_block_benchmark_before_data_changes(self):
        result = self.run_script("benchmark-seckill.sh", "pending")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Pending orders exist", result.stderr)
        self.assertFalse(any(command == "wrk" or payload or "DEL" in arguments
                             for command, arguments, payload in self.commands()))

    def test_failed_load_preserves_data_and_returns_failure(self):
        result = self.run_script("benchmark-seckill.sh", "wrk-failed")
        self.assertEqual(result.returncode, 2)
        self.assertIn("token pool exhausted", result.stdout)
        self.assertIn("preserved for diagnosis", result.stderr)
        self.assertNotIn("BENCHMARK_RESULT", result.stdout)
        self.assertNotIn("Cleaning benchmark-only", result.stdout)

    def test_stock_mismatch_preserves_evidence(self):
        result = self.run_script("benchmark-seckill.sh", "stock-mismatch")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MySQL stock does not match", result.stderr)
        self.assertIn("preserved for diagnosis", result.stderr)
        self.assertNotIn("Cleaning benchmark-only", result.stdout)


class LuaScriptTests(unittest.TestCase):
    def test_independent_token_pool_and_summary(self):
        library = ctypes.util.find_library("luajit-5.1")
        if not library:
            self.skipTest("Install libluajit-5.1-2 to run Lua regression checks")
        runtime = ctypes.CDLL(library)
        runtime.luaL_newstate.restype = ctypes.c_void_p
        runtime.luaL_openlibs.argtypes = [ctypes.c_void_p]
        runtime.luaL_loadfile.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        runtime.lua_pcall.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int]
        runtime.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
        runtime.lua_tolstring.restype = ctypes.c_char_p
        runtime.lua_close.argtypes = [ctypes.c_void_p]
        state = runtime.luaL_newstate()
        self.addCleanup(runtime.lua_close, state)
        runtime.luaL_openlibs(state)
        result = runtime.luaL_loadfile(state, os.fsencode(REPOSITORY_ROOT / "scripts/tests/wrk-seckill-load-test.lua"))
        if result == 0:
            result = runtime.lua_pcall(state, 0, 0, 0)
        if result != 0:
            self.fail(runtime.lua_tolstring(state, -1, None).decode())


if __name__ == "__main__":
    os.chdir(REPOSITORY_ROOT)
    unittest.main(verbosity=2)
