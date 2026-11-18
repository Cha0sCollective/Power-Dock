"""Save and reopen two disposable Power Dock fixtures in fresh Minecraft processes."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).absolute().parents[1]


def run_phase(sable, phase, directory, offline):
    arguments = ["cmd.exe", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "./gradlew"]
    arguments += ["--no-daemon", "--console=plain"]
    if offline:
        arguments.append("--offline")
    arguments += [f"-PsableVersion={sable}", f"-PdockLifecycleRun={directory.name}",
                  f"-PdockLifecyclePhase={phase}", "runGameTestServer"]
    log = directory / f"{phase}.log"
    print(f"{sable} {phase} - {log.relative_to(ROOT)}", flush=True)
    with log.open("wb") as output:
        process = subprocess.run(arguments, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, check=False)
    contents = log.read_text(encoding="utf-8", errors="replace")
    if f"Sable {sable} (sable)" not in contents:
        raise RuntimeError(f"Expected Sable {sable} in {log}")
    if phase == "save-abrupt":
        passed = (process.returncode != 0 and "non-zero exit value 86" in contents
                  and "POWER_DOCK_RESTART_ABRUPT_CHECKPOINT_SAVED" in contents)
    else:
        marker = ("POWER_DOCK_RESTART_RESTORED" if phase == "restore"
                  else "POWER_DOCK_RESTART_CLEAN_CHECKPOINT_READY")
        passed = process.returncode == 0 and "All 1 required tests passed" in contents and marker in contents
    if not passed:
        print("\n".join(contents.splitlines()[-35:]), flush=True)
        raise RuntimeError(f"The dock restart phase did not finish as expected. See {log}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sable", choices=("2.0.5", "2.0.6"), default="2.0.6")
    parser.add_argument("--case", choices=("clean", "abrupt", "both"), default="both")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    root = ROOT / "build" / "dock-restart"
    root.mkdir(parents=True, exist_ok=True)
    for case in ("clean", "abrupt") if args.case == "both" else (args.case,):
        directory = Path(tempfile.mkdtemp(prefix=f"{case}-", dir=root))
        run_phase(args.sable, f"save-{case}", directory, args.offline)
        if not (directory / "fixture-world" / "power-dock-restart.properties").is_file():
            raise RuntimeError(f"The {case} fixture did not leave a checkpoint")
        run_phase(args.sable, "restore", directory, args.offline)
        print(f"{args.sable} {case} restart passed", flush=True)


if __name__ == "__main__":
    main()
