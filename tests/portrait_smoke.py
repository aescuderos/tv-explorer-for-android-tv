"""UI regression test on an unlocked phone with a populated country catalogue."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET


ADB = os.environ.get("ADB", "adb")
REMOTE = "/sdcard/tv-explorer-smoke.xml"


def adb(*args):
    return subprocess.check_output([ADB, *args], text=True)


def tree():
    adb("shell", "uiautomator", "dump", REMOTE)
    return ET.fromstring(adb("shell", "cat", REMOTE))


def wait_for(predicate, message):
    until = time.monotonic() + 120
    while time.monotonic() < until:
        root = tree()
        if predicate(root):
            return root
        time.sleep(1)
    raise AssertionError(message)


def find(root, **attributes):
    return next((n for n in root.iter("node") if all(n.get(k) == v for k, v in attributes.items())), None)


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def tap(node, hold=False):
    x1, y1, x2, y2 = bounds(node)
    x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
    if hold:
        adb("shell", "input", "swipe", x, y, x, y, "900")
    else:
        adb("shell", "input", "tap", x, y)


def press(label):
    root = wait_for(lambda r: find(r, text=label) is not None, "Missing control: " + label)
    matches = [n for n in root.iter("node") if n.get("text") == label]
    tap(matches[-1])


def control(description):
    root = wait_for(lambda r: find(r, **{"content-desc": description}) is not None, description)
    tap(find(root, **{"content-desc": description}))


def grid(root):
    return find(root, **{"class": "android.widget.GridView"})


def columns(root, count):
    g = grid(root)
    if g is None or len(g) < count:
        return False
    first_y = bounds(g[0])[1]
    return sum(bounds(cell)[1] == first_y for cell in g) == count


def category_all():
    control("Elegir categoría")
    root = wait_for(
        lambda r: any(n.get("text", "").startswith("Todos (") for n in r.iter("node")),
        "Missing category list",
    )
    tap(next(n for n in root.iter("node") if n.get("text", "").startswith("Todos (")))


def run():
    adb("shell", "wm", "user-rotation", "lock", "0")
    adb("shell", "am", "force-stop", "com.tvexplorer")
    adb("shell", "am", "start", "-n", "com.tvexplorer/.MainActivity")
    root = wait_for(lambda r: columns(r, 2), "Portrait grid must have two columns")
    control("Elegir país")
    wait_for(lambda r: find(r, text="País") is not None, "Country picker did not open")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    category_all()
    adb("shell", "wm", "user-rotation", "lock", "1")
    wait_for(lambda r: columns(r, 5), "Wide layout must retain five columns")
    adb("shell", "wm", "user-rotation", "lock", "0")

    root = wait_for(lambda r: columns(r, 2), "Category grid is empty")
    first = grid(root)[0]
    channel = next(n.get("text") for n in first.iter("node") if n.get("text") not in ("", "★", None))
    tap(first, hold=True)
    root = wait_for(
        lambda r: find(r, text="Añadir a favoritos") is not None or find(r, text="Quitar de favoritos") is not None,
        "Long press did not open channel options",
    )
    added = find(root, text="Añadir a favoritos") is not None
    if added:
        press("Añadir a favoritos")
    else:
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    press("Favoritos")
    wait_for(lambda r: find(r, text=channel) is not None, "Favorites button did not filter")
    adb("shell", "wm", "user-rotation", "lock", "1")
    root = wait_for(lambda r: grid(r) is not None
                    and find(r, **{"class": "android.widget.ListView"}) is not None,
                    "Wide navigation did not appear")
    assert find(root, text=channel) is not None, "Rotation lost selected favorite"
    adb("shell", "wm", "user-rotation", "lock", "0")
    root = wait_for(lambda r: find(r, text="Favoritos") is not None and grid(r) is not None,
                    "Portrait controls did not return")
    assert find(root, text=channel) is not None, "Portrait rotation lost selected favorite"
    if added:
        tap(grid(root)[0], hold=True)
        press("Quitar de favoritos")
    category_all()

    press("Buscar")
    root = wait_for(lambda r: find(r, **{"class": "android.widget.EditText"}) is not None, "Search did not open")
    edit = find(root, **{"class": "android.widget.EditText"})
    tap(edit)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in edit.get("text", ""):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    adb("shell", "input", "text", "28%skanala")
    press("Buscar")
    wait_for(lambda r: find(r, text="28 kanala") is not None and columns(r, 1),
             "Search did not produce the expected channel")
    category_all()
    control("Más opciones")
    wait_for(lambda r: find(r, text="Actualizar lista") is not None, "Refresh menu is inaccessible")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    print("PASS: portrait grid, country/category/search controls, favorites, rotation and refresh menu")


rotation = adb("shell", "wm", "user-rotation").strip().split()
try:
    run()
finally:
    adb("shell", "wm", "user-rotation", *rotation)
    adb("shell", "rm", "-f", REMOTE)
