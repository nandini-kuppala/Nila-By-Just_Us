"""Push the eval photos to the phone, run the lab service headlessly, pull and score the results.

    python3 tools/run_eval.py detector|full [--only e01,e02] [--set eval|demo] [--no-push]
"""
import os, subprocess, sys, time

ADB = [os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "-s", "10BFAT1T4A000XP"]
HERE = os.path.dirname(os.path.abspath(__file__))
PHONE = "/sdcard/Android/data/com.nila.babyproof/files"

def adb(*a, check=True, capture=False):
    return subprocess.run(ADB + list(a), check=check, capture_output=capture, text=True)

def main():
    mode = sys.argv[1]
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    s = sys.argv[sys.argv.index("--set") + 1] if "--set" in sys.argv else "eval"
    local = os.path.join(HERE, "..", s)
    if "--no-push" not in sys.argv:
        adb("shell", f"rm -rf {PHONE}/{s}/results/{mode} {PHONE}/{s}/results/{mode}.done")
        for f in sorted(os.listdir(os.path.join(local, "images"))):
            if f.endswith(".jpg"):
                adb("push", os.path.join(local, "images", f), f"{PHONE}/{s}/images/{f}", capture=True)
    adb("shell", "am force-stop com.nila", check=False)
    adb("shell", f"rm -f {PHONE}/{s}/results/{mode}.done")
    adb("logcat", "-c")
    # After a reinstall the service sometimes does not start until the app has been opened once.
    adb("shell", "am force-stop com.nila.babyproof; input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n com.nila.babyproof/.MainActivity", check=False, capture=True)
    time.sleep(3)
    extra = ["--es", "only", only] if only else []
    adb("shell", "am", "start-foreground-service", "-n", "com.nila.babyproof/.lab.LabService",
        "--es", "mode", mode, "--es", "set", s, *extra, capture=True)
    t = time.time()
    while True:
        # vivo freezes an app whose screen is off, foreground service or not:
        # keep the screen on and the lab's activity (FLAG_KEEP_SCREEN_ON) in front.
        adb("shell", "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n com.nila.babyproof/.MainActivity >/dev/null 2>&1", check=False)
        time.sleep(10)
        log = adb("shell", "logcat -d | grep -E 'BabyproofLab'", capture=True, check=False).stdout
        last = [l for l in log.splitlines() if l.strip()][-1:] or ["..."]
        temp = adb("shell", "dumpsys battery | grep temperature", capture=True, check=False).stdout.split(":")[-1].strip()
        temp = f"{int(temp) / 10:.1f}C" if temp.isdigit() else "?"
        print(f"  {int(time.time()-t)}s {temp} {last[0][-100:]}", flush=True)
        done = adb("shell", f"cat {PHONE}/{s}/results/{mode}.done 2>/dev/null", capture=True, check=False).stdout.strip()
        if done:
            print("  lab:", done); break
        if time.time() - t > 3600: print("timeout"); break
    dest = os.path.join(local, "results")
    os.makedirs(dest, exist_ok=True)
    adb("pull", f"{PHONE}/{s}/results/{mode}", dest, capture=True)
    if s == "eval":
        subprocess.run([sys.executable, os.path.join(HERE, "score.py"), os.path.join(dest, mode), "--verbose"])

if __name__ == "__main__":
    main()
