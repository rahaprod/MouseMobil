#!/usr/bin/env python3
"""MouseMobile - серверная часть для ПК: мышь, клавиатура и трансляция экрана.

Запуск:  pip install pynput mss pillow   ->   python mousemobile_server.py

Управление (TCP 8765, строки UTF-8): сначала "AUTH <pin>" -> OK / NO / BAN, затем команды:
  MOVE dx dy | ABS x y (0..10000) | CLICK left|right|middle | DOWN b | UP b
  SCROLL n | TEXT <base64 utf-8> | KEY a+b+c
Экран (TCP 8766): после AUTH сервер шлёт кадры: 4 байта длины (big-endian) + JPEG.
Поиск ПК (UDP 8767): на "MOUSEMOBILE?" отвечает "MOUSEMOBILE <имя ПК>".
"""
import sys

if sys.platform == "win32":  # физические пиксели, чтобы координаты мыши и снимка совпадали
    import ctypes
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass

import base64
import hmac
import io
import secrets
import socket
import struct
import threading
import time

import mss
from PIL import Image, ImageDraw
from pynput.keyboard import Controller as Keyboard, Key
from pynput.mouse import Button, Controller as Mouse

CTRL_PORT, SCREEN_PORT, DISCOVERY_PORT = 8765, 8766, 8767
FPS, MAX_WIDTH, JPEG_QUALITY = 10, 1280, 55
MAX_FAILS, BAN_SECONDS = 5, 300

PIN = f"{secrets.randbelow(10 ** 6):06d}"
mouse, kb = Mouse(), Keyboard()
fails = {}  # ip -> (число ошибок, время окончания бана)
MON = {"left": 0, "top": 0, "width": 1, "height": 1}  # основной монитор (обновляется потоком экрана)

BTN = {"left": Button.left, "right": Button.right, "middle": Button.middle}

KEYS = {}
for _n in ("enter", "backspace", "tab", "esc", "space", "up", "down", "left", "right", "delete",
           "home", "end", "page_up", "page_down", "ctrl", "alt", "shift", "cmd"):
    KEYS[_n] = getattr(Key, _n, None)
for _i in range(1, 13):
    KEYS[f"f{_i}"] = getattr(Key, f"f{_i}", None)
KEYS.update({
    "win": getattr(Key, "cmd", None),
    "pageup": getattr(Key, "page_up", None),
    "pagedown": getattr(Key, "page_down", None),
    "volume_up": getattr(Key, "media_volume_up", None),
    "volume_down": getattr(Key, "media_volume_down", None),
    "volume_mute": getattr(Key, "media_volume_mute", None),
    "play_pause": getattr(Key, "media_play_pause", None),
    "next_track": getattr(Key, "media_next", None),
    "prev_track": getattr(Key, "media_previous", None),
})


def hotkey(spec):
    """'ctrl+c', 'alt+tab', 'enter', 'win+d' - нажать все клавиши, затем отпустить в обратном порядке."""
    keys = []
    for token in spec.lower().split("+"):
        k = KEYS.get(token)
        if k is None and len(token) == 1:
            k = token
        if k is None:
            return
        keys.append(k)
    for k in keys:
        kb.press(k)
    for k in reversed(keys):
        kb.release(k)


def handle(line):
    cmd, _, arg = line.partition(" ")
    if cmd == "MOVE":
        dx, dy = map(int, arg.split())
        mouse.move(dx, dy)
    elif cmd == "ABS":
        x, y = map(int, arg.split())
        x, y = min(max(x, 0), 10000), min(max(y, 0), 10000)
        m = MON
        mouse.position = (m["left"] + int((m["width"] - 1) * x / 10000),
                          m["top"] + int((m["height"] - 1) * y / 10000))
    elif cmd == "CLICK":
        mouse.click(BTN[arg])
    elif cmd == "DOWN":
        mouse.press(BTN[arg])
    elif cmd == "UP":
        mouse.release(BTN[arg])
    elif cmd == "SCROLL":
        mouse.scroll(0, max(-50, min(50, int(arg))))
    elif cmd == "TEXT":
        kb.type(base64.b64decode(arg).decode("utf-8"))
    elif cmd == "KEY":
        hotkey(arg)


def control(conn, f):
    print("Подключено устройство:", conn.getpeername()[0], flush=True)
    try:
        while True:
            raw = f.readline(1 << 20)
            if not raw:
                break
            try:
                handle(raw.decode("utf-8").strip())
            except Exception as e:  # плохая команда не должна ронять соединение
                print("Ошибка команды:", e, flush=True)
    finally:
        try:
            mouse.release(Button.left)
        except Exception:
            pass
        print("Устройство отключено", flush=True)


def stream(conn, f):
    global MON
    with mss.mss() as sct:
        period = 1.0 / FPS
        while True:
            t0 = time.time()
            mon = sct.monitors[1]
            MON = mon
            shot = sct.grab(mon)
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            scale = min(1.0, MAX_WIDTH / img.width)
            if scale < 1.0:
                img = img.resize((int(img.width * scale), int(img.height * scale)))
            px, py = mouse.position  # рисуем курсор: mss его не снимает
            cx, cy = (px - mon["left"]) * scale, (py - mon["top"]) * scale
            ImageDraw.Draw(img).polygon(
                [(cx, cy), (cx, cy + 17), (cx + 4, cy + 13), (cx + 9, cy + 20),
                 (cx + 12, cy + 19), (cx + 8, cy + 12), (cx + 13, cy + 12)],
                fill="white", outline="black")
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=JPEG_QUALITY)
            data = buf.getvalue()
            conn.sendall(struct.pack(">I", len(data)) + data)
            time.sleep(max(0.0, period - (time.time() - t0)))


def auth(conn, f, ip):
    count, until = fails.get(ip, (0, 0.0))
    if until > time.time():
        conn.sendall(b"BAN\n")
        return False
    line = f.readline(128).decode("utf-8", "replace").strip()
    if line.startswith("AUTH ") and hmac.compare_digest(line[5:].strip().encode(), PIN.encode()):
        fails.pop(ip, None)
        conn.sendall(b"OK\n")
        return True
    count += 1
    fails[ip] = (count, time.time() + BAN_SECONDS if count >= MAX_FAILS else 0.0)
    conn.sendall(b"NO\n")
    return False


def client(conn, ip, handler):
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.settimeout(10)
        f = conn.makefile("rb")
        if not auth(conn, f, ip):
            return
        conn.settimeout(None)
        handler(conn, f)
    except (OSError, ValueError):
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass


def serve(port, handler):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(5)
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=client, args=(conn, addr[0], handler), daemon=True).start()


def discovery():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", DISCOVERY_PORT))
    name = socket.gethostname().encode("utf-8", "replace")[:60]
    while True:
        data, addr = s.recvfrom(64)
        if data.startswith(b"MOUSEMOBILE?"):
            s.sendto(b"MOUSEMOBILE " + name, addr)


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    with mss.mss() as _sct:
        MON = _sct.monitors[1]
    for target, args in ((serve, (CTRL_PORT, control)), (serve, (SCREEN_PORT, stream)), (discovery, ())):
        threading.Thread(target=target, args=args, daemon=True).start()
    print("=" * 46)
    print(" MouseMobile - сервер запущен")
    print(f" IP этого ПК: {local_ip()}")
    print(f" PIN: {PIN}")
    print(" В приложении: «Найти ПК» (или введите IP) и PIN.")
    print(" Трафик не шифруется - только для домашней Wi-Fi сети.")
    print(" Ctrl+C - остановить.")
    print("=" * 46, flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
