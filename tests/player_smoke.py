"""Verify embedded video and remote-style controls on an unlocked phone."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
REMOTE = "/sdcard/tv-player-smoke.xml"


def adb(*args):
    return subprocess.check_output([ADB, *args], text=True)


def nodes():
    adb("shell", "uiautomator", "dump", REMOTE)
    return list(ET.fromstring(adb("shell", "cat", REMOTE)).iter("node"))


def wait_for(predicate, message, retry=None, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = nodes()
        if predicate(tree):
            return tree
        if retry:
            retry()
        time.sleep(1)
    raise AssertionError(message + ": " + str([(n.get("content-desc"), n.get("bounds"))
                                             for n in tree if n.get("content-desc") or n is tree[0]]))


def has_text(tree, value):
    return any(n.get("text") == value for n in tree)


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def press(value):
    tree = wait_for(lambda t: has_text(t, value), "Missing control: " + value)
    tap(next(n for n in reversed(tree) if n.get("text") == value))


def press_icon(label):
    tree = wait_for(lambda t: any(n.get("content-desc") == label for n in t), "Missing icon: " + label)
    tap(next(n for n in tree if n.get("content-desc") == label))


def reveal():
    adb("shell", "input", "tap", "300", "300")


def tap_icon(label):
    reveal()
    tree = wait_for(lambda t: any(n.get("content-desc") == label for n in t), "Missing icon: " + label)
    tap(next(n for n in tree if n.get("content-desc") == label))


def landscape(tree):
    coords = list(map(int, re.findall(r"\d+", tree[0].get("bounds"))))
    return coords[2] - coords[0] > coords[3] - coords[1]


def top_activity():
    return adb("shell", "dumpsys", "activity", "activities")


rotation = adb("shell", "wm", "user-rotation").strip().split()
try:
    adb("shell", "wm", "user-rotation", "lock", "0")
    adb("shell", "am", "force-stop", "com.tvexplorer")
    adb("shell", "am", "start", "-n", "com.tvexplorer/.MainActivity")
    wait_for(lambda t: has_text(t, "Buscar"), "Channel catalog did not open")
    press("Buscar")
    tree = wait_for(lambda t: any(n.get("class") == "android.widget.EditText" for n in t), "Search missing")
    edit = next(n for n in tree if n.get("class") == "android.widget.EditText")
    tap(edit)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in edit.get("text", ""):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    adb("shell", "input", "text", "28%skanala")
    press("Buscar")
    tree = wait_for(lambda t: has_text(t, "28 kanala"), "Live channel not found")
    tap(next(n for n in tree if n.get("text") == "28 kanala"))
    wait_for(lambda t: "com.tvexplorer/.PlayerActivity" in top_activity(), "Embedded player did not open")
    time.sleep(8)
    reveal()
    tree = wait_for(lambda t: any("En directo" in n.get("text", "") for n in t), "Stream did not play")
    info = next(n for n in tree if n.get("content-desc") == "Información sobre LibVLC")
    back = next(n for n in tree if n.get("content-desc") == "Volver")
    pause = next(n for n in tree if n.get("content-desc") == "Pausar")
    full = next(n for n in tree if n.get("content-desc") == "Pantalla completa")
    screen_width = max(int(re.findall(r"\d+", n.get("bounds"))[2]) for n in tree)
    assert int(re.findall(r"\d+", info.get("bounds"))[2]) > screen_width * 0.85, "Info icon is not right-aligned"
    assert int(re.findall(r"\d+", back.get("bounds"))[0]) < screen_width * 0.15, "Back icon is not left-aligned"
    group_left = int(re.findall(r"\d+", pause.get("bounds"))[0])
    group_right = int(re.findall(r"\d+", full.get("bounds"))[2])
    assert abs((group_left + group_right) / 2 - screen_width / 2) < screen_width * 0.05, "Controls are not centered"
    tap(pause)
    tree = wait_for(lambda t: has_text(t, "En pausa") and any(n.get("content-desc") == "Reproducir" for n in t), "Pause did not update controls")
    tap(next(n for n in tree if n.get("content-desc") == "Reproducir"))
    wait_for(lambda t: any("En directo" in n.get("text", "") for n in t), "Stream did not resume")
    tap_icon("Emisiones alternativas")
    wait_for(lambda t: has_text(t, "Emisiones disponibles") and has_text(t, "Emisión 1 (actual)"),
             "Stream selection did not open")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap_icon("Pantalla completa")
    wait_for(lambda t: landscape(t) and any(n.get("content-desc") == "Salir de pantalla completa" for n in t),
             "Full screen did not rotate and expose an exit control")
    assert "com.tvexplorer/.PlayerActivity" in top_activity(), "Full screen exited the player"
    tap_icon("Salir de pantalla completa")
    wait_for(lambda t: any(n.get("content-desc") == "Pantalla completa" for n in t),
             "Exiting full screen did not restore the control", retry=reveal, timeout=20)
    window = adb("shell", "dumpsys", "window")
    assert "mLastOrientation=-1" in window, "Exiting full screen did not restore the requested orientation"
    time.sleep(6)
    adb("shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
    wait_for(lambda t: any(n.get("content-desc") == "Volver" for n in t), "Remote control did not reveal controls")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_for(lambda t: "com.tvexplorer/.MainActivity" in top_activity(), "Back did not return to catalog")
    print("PASS: internal player, playback, pause/resume, stream selection, fullscreen and remote controls")
finally:
    adb("shell", "wm", "user-rotation", *rotation)
    adb("shell", "rm", "-f", REMOTE)
