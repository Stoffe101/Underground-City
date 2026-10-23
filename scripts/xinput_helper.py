#!/usr/bin/env python3
"""Inject real keyboard and mouse events into an X11 display (for the scripted-player client tests).

Why: some mods (Immersive Vehicles) poll the keyboard state straight from GLFW, so synthetic Minecraft key events
do not reach them, and java.awt.Robot is unavailable because Minecraft starts the JVM headless. The X server's XTEST
extension (libXtst, present with Xvfb) delivers genuine input to whichever window is under the pointer.

Reads one command per line on stdin and answers "ok" or "error <reason>":

    down <key>      press a key        (w a s d space shift ctrl r f e z x c v b g h j k l 1..9 enter tab escape ...)
    up <key>        release it
    tap <key>       press and release
    button <n> <1|0>   mouse button n (1 left, 2 middle, 3 right) pressed (1) or released (0)
    move <x> <y>    warp the pointer (use the window centre first, so the keyboard focus follows it)
    quit

Needs DISPLAY (set by xvfb-run). Never used in normal play; nothing is installed, only libX11 and libXtst are loaded.
"""
from __future__ import annotations

import ctypes
import sys

ALIASES = {"space": "space", "shift": "Shift_L", "ctrl": "Control_L", "enter": "Return", "tab": "Tab", "escape": "Escape",
           "esc": "Escape", "backspace": "BackSpace", "up": "Up", "down": "Down", "left": "Left", "right": "Right"}


def main() -> int:
    x11 = ctypes.CDLL("libX11.so.6")
    xtst = ctypes.CDLL("libXtst.so.6")
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
    x11.XStringToKeysym.restype = ctypes.c_ulong
    x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    x11.XKeysymToKeycode.restype = ctypes.c_ubyte
    x11.XFlush.argtypes = [ctypes.c_void_p]
    xtst.XTestFakeKeyEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
    xtst.XTestFakeButtonEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
    xtst.XTestFakeMotionEvent.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
    display = x11.XOpenDisplay(None)
    if not display:
        print("error cannot open the X display (is DISPLAY set?)", flush=True)
        return 1

    def keycode(name: str) -> int:
        symbol = ALIASES.get(name.lower(), name)
        code = x11.XKeysymToKeycode(display, x11.XStringToKeysym(symbol.encode()))
        if not code:
            raise ValueError(f"unknown key {name}")
        return code

    for raw in sys.stdin:
        parts = raw.split()
        if not parts:
            continue
        try:
            command = parts[0]
            if command == "quit":
                break
            if command in ("down", "up", "tap"):
                code = keycode(parts[1])
                if command in ("down", "tap"):
                    xtst.XTestFakeKeyEvent(display, code, 1, 0)
                if command in ("up", "tap"):
                    xtst.XTestFakeKeyEvent(display, code, 0, 0)
            elif command == "button":
                xtst.XTestFakeButtonEvent(display, int(parts[1]), int(parts[2]), 0)
            elif command == "click":
                # a press and its release in one flush, so the game reads both in one input poll: sent as two commands, a slow software-rendered frame can run four
                # catch-up ticks between them, and the game repeats "use" every four ticks while the button is down (one click opened a door and shut it again)
                xtst.XTestFakeButtonEvent(display, int(parts[1]), 1, 0)
                xtst.XTestFakeButtonEvent(display, int(parts[1]), 0, 0)
            elif command == "move":
                xtst.XTestFakeMotionEvent(display, 0, int(parts[1]), int(parts[2]), 0)
            else:
                raise ValueError(f"unknown command {command}")
            x11.XFlush(display)
            print("ok", flush=True)
        except (ValueError, IndexError) as error:
            print(f"error {error}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
