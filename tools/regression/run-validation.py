#!/usr/bin/env python3
"""Build and exercise both mods in an isolated, peaceful Fabric server.

Only the launcher, installed server binaries, libraries, versions and accepted EULA are copied
from --server-template. No production world, settings, operators or mods change.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def java_home(explicit):
    candidates = [Path(explicit)] if explicit else []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    candidates.extend(sorted((Path.home() / ".gradle" / "jdks").glob("*25*")))
    for candidate in candidates:
        binary = candidate / "bin" / ("java.exe" if os.name == "nt" else "java")
        if binary.is_file():
            version = subprocess.run([str(binary), "-version"], capture_output=True, text=True)
            if re.search(r'version "(?:2[5-9]|[3-9]\d)', version.stderr + version.stdout):
                return candidate
    raise RuntimeError("Supply --java-home pointing to JDK 25 or newer")


def build(home, consumer, output):
    env = dict(os.environ, JAVA_HOME=str(home))
    for project, args in [(ROOT, ["test", "build"]), (consumer, ["build"])]:
        if project == consumer:
            (consumer / "libs").mkdir(exist_ok=True)
            shutil.copy2(ROOT / "build/libs/navigation-ai-0.1.0.jar", consumer / "libs/navigation-ai-0.1.0.jar")
        wrapper = str(project / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        with (output / ("build-library.log" if project == ROOT else "build-consumer.log")).open("w", encoding="utf8") as log:
            result = subprocess.run([wrapper, *args, "--console=plain"], cwd=project, env=env,
                                    stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"Build failed in {project}; see {log.name}")


class Server:
    def __init__(self, binary, directory, launcher, log, profile=False):
        flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
        profiling = [f"-Xlog:gc:file={log.stem}.gc.log:time,uptime,level,tags",
                     f"-XX:StartFlightRecording=filename={log.stem}.jfr,settings=profile,dumponexit=true,maxsize=256m"] if profile else []
        self.process = subprocess.Popen([str(binary), "-Xms1G", "-Xmx3G", *profiling, "-jar", launcher.name, "nogui"],
                                        cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, encoding="utf8", errors="replace", bufsize=1, **flags)
        self.lines = queue.Queue()
        self.stop_request = log.parent / "stop-requested"
        self.log = log.open("w", encoding="utf8")
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def read(self):
        for line in self.process.stdout:
            self.log.write(line)
            self.log.flush()
            self.lines.put(line.rstrip())

    def command(self, command):
        print(f"> {command}", flush=True)
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def wait(self, expression, timeout):
        matcher = re.compile(expression)
        until = time.monotonic() + timeout
        observed = []
        while time.monotonic() < until:
            if self.stop_request.exists():
                raise RuntimeError("Validation stopped by stop-requested file")
            try:
                line = self.lines.get(timeout=min(1, max(.01, until - time.monotonic())))
            except queue.Empty:
                if self.process.poll() is not None:
                    raise RuntimeError(f"Server exited with {self.process.returncode}")
                continue
            if re.search(r"(?:REALITY|ACTIVITIES|REGIONS|PERSIST|REGRESSION|REVIEW|FIXES|TRANSITIONS|SELFTEST|TICKET|MEASURE|LOAD|LIBRARY|EXCAVATION|COMBAT|VILLAGERS) (?:START|PASS|FAIL|BAD|ERROR|ROUND|PROGRESS|DONE|READY)|ERROR|Exception", line):
                print(line, flush=True)
                observed.append(line)
            if matcher.search(line):
                return line, observed
        raise TimeoutError(f"No terminal marker matching {expression} within {timeout}s")

    def close(self):
        if self.process.poll() is None:
            try:
                self.command("stop")
            except BrokenPipeError:
                pass
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=15)
        self.thread.join(timeout=10)
        self.log.close()


def setup(template, directory, consumer, seed, port):
    eula = template / "eula.txt"
    if not eula.is_file() or not re.search(r"(?im)^eula\s*=\s*true\s*$", eula.read_text()):
        raise RuntimeError("The template server must already have an accepted EULA")
    directory.mkdir(parents=True, exist_ok=False)
    for name in ["libraries", "versions"]:
        shutil.copytree(template / name, directory / name)
    installed = template / ".fabric/server"
    if not (installed / "26.2-server.jar").is_file() or not (installed / "fabric-loader-server-0.19.5-minecraft-26.2.jar").is_file():
        raise RuntimeError("Start the template once to populate .fabric/server for Minecraft 26.2 / Fabric 0.19.5")
    # Copy the installed launcher cache, never processed/remapped mods. This
    # avoids a fresh network installation on every test run.
    shutil.copytree(installed, directory / ".fabric/server")
    launchers = list(template.glob("fabric-server-*-loader.0.19.5-*.jar"))
    if len(launchers) != 1:
        raise RuntimeError("Expected one Fabric 0.19.5 launcher in template")
    shutil.copy2(launchers[0], directory)
    shutil.copy2(eula, directory / "eula.txt")
    mods = directory / "mods"
    mods.mkdir()
    api = list((template / "mods").glob("fabric-api-*.jar"))
    if len(api) != 1:
        raise RuntimeError("Expected one Fabric API JAR in template")
    for jar in [api[0], ROOT / "build/libs/navigation-ai-0.1.0.jar", consumer / "build/libs/navigation-ai-practice-test-0.1.0.jar"]:
        shutil.copy2(jar, mods / jar.name)
    (directory / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=validation-world\n"
        f"level-seed={seed}\nlevel-type=minecraft:normal\ndifficulty=peaceful\ngamemode=survival\n"
        "spawn-protection=0\nview-distance=4\nsimulation-distance=4\npause-when-empty-seconds=0\n"
        "enable-rcon=false\nmax-tick-time=120000\nmotd=Navigation AI isolated validation\n", encoding="utf8")
    return directory / launchers[0].name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-template", type=Path, required=True)
    parser.add_argument("--consumer", type=Path, default=ROOT.parent / "Practicetestmod")
    parser.add_argument("--java-home")
    parser.add_argument("--seed", default="8675309")
    parser.add_argument("--port", type=int, default=25582)
    parser.add_argument("--distance", type=int, default=1000)
    parser.add_argument("--suite", default="library,excavation,combat,core,activities,persistence,excavation-persistence,regions,journey,mixed,load")
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--keep-going", action="store_true")
    parser.add_argument("--profile", action="store_true", help="Save GC log and Java Flight Recorder profile")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or ROOT / "build/validation" / time.strftime("%Y%m%d-%H%M%S")
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {"seed": args.seed, "difficulty": "peaceful", "distance": args.distance,
              "output": str(output), "stages": [], "success": False}
    server = None
    try:
        home = java_home(args.java_home)
        if not args.no_build:
            print("Building library and consumer...", flush=True)
            build(home, args.consumer, output)
        tests = [ET.parse(path).getroot() for path in (ROOT / "build/test-results/test").glob("TEST-*.xml")]
        report["junit"] = {key: sum(int(test.get(key, "0")) for test in tests) for key in ["tests", "failures", "errors"]}
        report["commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        report["sourceSha256"] = {}
        for project in [ROOT,args.consumer]:
            paths = subprocess.check_output(["git","ls-files","--cached","--others","--exclude-standard"],cwd=project,text=True).splitlines()
            for name in paths:
                path=project/name
                if path.is_file() and path.suffix in {".java",".gradle",".properties",".py"}:
                    report["sourceSha256"][project.name+"/"+name]=hashlib.sha256(path.read_bytes()).hexdigest()
        report["workingTree"] = subprocess.check_output(["git","status","--short"],cwd=ROOT,text=True)
        report["jars"] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in
                          [ROOT / "build/libs/navigation-ai-0.1.0.jar", args.consumer / "build/libs/navigation-ai-practice-test-0.1.0.jar"]}
        directory = output / "server"
        launcher = setup(args.server_template.resolve(), directory, args.consumer, args.seed, args.port)
        print(f"New peaceful world: {directory / 'validation-world'}", flush=True)
        server = Server(home / "bin" / ("java.exe" if os.name == "nt" else "java"), directory, launcher, output / "server.log", args.profile)
        server.wait(r'Done \([\d.]+s\)!', 300)
        server.command("difficulty peaceful")
        stages = {
            "villagers": [("navvillagers", r"VILLAGERS (PASS|FAIL) passed=", 900)],
            "villager-persistence": [("forceload add 11000 11000", None, 0),
                                      ("navvillagers-save", r"VILLAGERS PERSIST (READY|FAIL)", 120),
                                      ("@restart", None, 0),
                                      ("navvillagers-restore", r"VILLAGERS PERSIST (PASS|FAIL)", 120)],
            "library": [("navlibrary", r"LIBRARY (PASS|FAIL) passed=", 600)],
            "excavation": [("navexcavation", r"EXCAVATION (PASS|FAIL) passed=", 900)],
            "combat": [("navcombat", r"COMBAT (PASS|FAIL) passed=", 600)],
            "excavation-persistence": [("navexcavatepersist prepare", r"EXCAVATE PERSIST (READY|FAIL)", 300),
                                       ("@restart", None, 0), ("navexcavatepersist verify", r"EXCAVATE PERSIST (PASS|FAIL)", 900)],
            "core": [("navtest selftest", r"SELFTEST (PASS|FAIL)", 120),
                     ("navtest ticket-regression", r"TICKET REGRESSION (PASS|FAIL)", 120),
                     ("navtest regression", r"REGRESSION (PASS|FAIL) passed=", 900),
                     ("navreview", r"REVIEW (PASS|FAIL) passed=", 900),
                     ("navtransitions", r"TRANSITIONS (PASS|FAIL) passed=", 600),
                     ("navfixes", r"FIXES (PASS|FAIL)", 900)],
            "activities": [("navactivities", r"ACTIVITIES (PASS|FAIL) passed=", 1200)],
            "transitions": [("navtransitions", r"TRANSITIONS (PASS|FAIL) passed=", 600)],
            "regions": [("navregions", r"REGIONS (PASS|FAIL)", 400)],
            "persistence": [("navpersist prepare", r"PERSIST (READY|FAIL)", 300),
                            ("@restart", None, 0), ("navpersist verify", r"PERSIST (PASS|FAIL)", 400)],
            "journey": [(f"navreality journey {args.distance}", r"REALITY (PASS|FAIL) mode=journey", 1900)],
            "mixed": [("navreality mixed", r"REALITY (PASS|FAIL) mode=mixed", 1000)],
            "load": [("navmeasure load", None, 0), ("navtest load 50", r"MEASURE DONE", 600)],
        }
        for suite in args.suite.split(","):
            for command, marker, timeout in stages[suite]:
                if command == "@restart":
                    server.close()
                    server = Server(home / "bin" / ("java.exe" if os.name == "nt" else "java"), directory, launcher, output / "server-restarted.log", args.profile)
                    server.wait(r'Done \([\d.]+s\)!', 300)
                    continue
                server.command(command)
                if marker is None:
                    continue
                start = time.monotonic()
                line, observed = server.wait(marker, timeout)
                ok = " FAIL" not in line and (" PASS" in line or "PERSIST READY" in line
                                              or command.startswith("navtest load") and any("LOAD PASS" in event for event in observed))
                stage = {"command": command, "success": ok, "seconds": time.monotonic() - start, "result": line, "events": observed}
                report["stages"].append(stage)
                (output / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf8")
                if not ok and not args.keep_going:
                    raise RuntimeError(f"Stage failed: {command}")
        report["success"] = bool(report["stages"]) and all(stage["success"] for stage in report["stages"])
    except Exception as failure:
        report["error"] = str(failure)
        print(f"VALIDATION ERROR: {failure}", flush=True)
    finally:
        if server:
            server.close()
        (output / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf8")
        print(f"Report: {output / 'report.json'}", flush=True)
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
