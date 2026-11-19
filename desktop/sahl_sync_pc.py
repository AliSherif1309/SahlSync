"""Sahl Sync for PC: share Windows folders with the Sahl Sync phone app over home Wi-Fi.

Double-click SahlSyncPC.exe, add folders, press Start sharing, then scan the QR code
from the phone (Sahl Sync > Local Wi-Fi sync > Scan QR from PC).
"""
import ipaddress
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

APP = "Sahl Sync for PC"
VERSION = "1.0.0"
STATE = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SahlSync"
SETTINGS = STATE / "pc.json"
PAIRING = STATE / "pairing.json"
LOG = STATE / "server.log"
STARTUP = Path(os.environ.get("APPDATA", Path.home())) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup" / "Sahl Sync for PC.cmd"

# Sahl palette (shared with the phone apps)
BG, PANEL, TEXT, MUTED, GOLD, DARK = "#0E1A24", "#13241F", "#EEF3F1", "#8FA39C", "#E3B85C", "#0B1E1A"


def serve(arguments):
    """Server mode: the same exe runs the Wi-Fi sync server in the background."""
    STATE.mkdir(parents=True, exist_ok=True)
    log = open(LOG, "a", encoding="utf-8")
    sys.stdout = sys.stdout or log
    sys.stderr = log
    import sahl_sync_lan
    sys.argv = ["sahl_sync_lan"] + arguments
    sahl_sync_lan.main()


def private_addresses():
    found = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("192.168.0.1", 9))  # No packet is sent; this picks the active network.
        found.append(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    result = []
    for address in found:
        try:
            ip = ipaddress.IPv4Address(address)
        except ValueError:
            continue
        if ip.is_private and not ip.is_loopback and address not in result:
            result.append(address)
    return result


def load_settings():
    try:
        return json.loads(SETTINGS.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


class App:
    def __init__(self, root, minimized):
        self.root, self.worker, self.codes = root, None, {}
        self.settings = load_settings()
        root.title(APP)
        root.configure(bg=BG)
        root.minsize(560, 600)
        root.protocol("WM_DELETE_WINDOW", self.close)
        style = ttk.Style()
        style.theme_use("clam")
        style.configure(".", background=BG, foreground=TEXT, fieldbackground=PANEL)
        style.configure("TButton", background=PANEL, foreground=TEXT, padding=8)
        style.configure("Gold.TButton", background=GOLD, foreground=DARK, padding=10)
        style.map("TButton", background=[("active", "#1C3530")])
        style.map("Gold.TButton", background=[("active", "#F0CB7A")])

        tk.Label(root, text="Sahl Sync for PC", bg=BG, fg=GOLD, font=("Segoe UI", 18, "bold")).pack(anchor="w", padx=20, pady=(16, 0))
        tk.Label(root, text=f"Share folders with your phone over home Wi-Fi.  v{VERSION}", bg=BG, fg=MUTED, font=("Segoe UI", 10)).pack(anchor="w", padx=20)

        net = tk.Frame(root, bg=BG)
        net.pack(fill="x", padx=20, pady=(14, 4))
        tk.Label(net, text="PC address", bg=BG, fg=TEXT).pack(side="left")
        self.address = ttk.Combobox(net, width=18, values=private_addresses())
        self.address.set(self.settings.get("address") or (self.address["values"][0] if self.address["values"] else ""))
        self.address.pack(side="left", padx=8)
        tk.Label(net, text="Port", bg=BG, fg=TEXT).pack(side="left")
        self.port = ttk.Spinbox(net, from_=1024, to=65535, width=7)
        self.port.set(self.settings.get("port", 8765))
        self.port.pack(side="left", padx=8)

        tk.Label(root, text="Shared folders", bg=BG, fg=TEXT, font=("Segoe UI", 11, "bold")).pack(anchor="w", padx=20, pady=(10, 2))
        self.folders = tk.Listbox(root, height=5, bg=PANEL, fg=TEXT, selectbackground=GOLD, selectforeground=DARK,
                                  borderwidth=0, highlightthickness=0, font=("Segoe UI", 10))
        self.folders.pack(fill="x", padx=20)
        for folder in self.settings.get("folders", []):
            self.folders.insert("end", folder)
        self.folders.bind("<<ListboxSelect>>", lambda _event: self.show_code())
        buttons = tk.Frame(root, bg=BG)
        buttons.pack(fill="x", padx=20, pady=6)
        ttk.Button(buttons, text="Add folder", command=self.add_folder).pack(side="left")
        ttk.Button(buttons, text="Remove", command=self.remove_folder).pack(side="left", padx=6)
        self.start = ttk.Button(buttons, text="Start sharing", style="Gold.TButton", command=self.toggle)
        self.start.pack(side="right")

        self.autostart = tk.BooleanVar(value=STARTUP.exists())
        tk.Checkbutton(root, text="Start with Windows and share automatically", variable=self.autostart, command=self.set_autostart,
                       bg=BG, fg=TEXT, selectcolor=PANEL, activebackground=BG, activeforeground=TEXT).pack(anchor="w", padx=16)

        self.status = tk.Label(root, text="Not sharing.", bg=BG, fg=MUTED, font=("Segoe UI", 10))
        self.status.pack(anchor="w", padx=20, pady=(8, 0))
        self.qr = tk.Label(root, bg=BG)
        self.qr.pack(pady=8)
        self.copy = ttk.Button(root, text="Copy pairing code", command=self.copy_code)
        tk.Label(root, text="On the phone: Sahl Sync > Local Wi-Fi sync > Scan QR from PC. Keep the code private.",
                 bg=BG, fg=MUTED, wraplength=520, font=("Segoe UI", 9)).pack(side="bottom", pady=10)
        if minimized:
            root.iconify()
            if self.folders.size():
                root.after(500, self.toggle)

    def save(self):
        STATE.mkdir(parents=True, exist_ok=True)
        data = {"address": self.address.get().strip(), "port": int(self.port.get() or 8765),
                "folders": list(self.folders.get(0, "end"))}
        SETTINGS.write_text(json.dumps(data, indent=1), encoding="utf-8")

    def add_folder(self):
        folder = filedialog.askdirectory(title="Choose a folder to share")
        if folder:
            folder = str(Path(folder))
            if folder not in self.folders.get(0, "end"):
                self.folders.insert("end", folder)
                self.save()
                if self.worker:
                    self.restart()

    def remove_folder(self):
        for index in reversed(self.folders.curselection()):
            self.folders.delete(index)
        self.save()
        if self.worker:
            self.restart()

    def command(self, arguments):
        if getattr(sys, "frozen", False):
            return [sys.executable, "serve"] + arguments
        return [sys.executable, str(Path(__file__).with_name("sahl_sync_pc.py")), "serve"] + arguments

    def toggle(self):
        if self.worker:
            self.stop()
            return
        folders = list(self.folders.get(0, "end"))
        address = self.address.get().strip()
        if not folders or not address:
            messagebox.showinfo(APP, "Add a folder and choose the PC address first.")
            return
        self.save()
        try:
            PAIRING.unlink(missing_ok=True)
        except OSError:
            pass
        arguments = [folders[0]]
        for extra in folders[1:]:
            arguments += ["--share", extra]
        arguments += ["--listen", address, "--port", str(int(self.port.get())), "--pairing-file", str(PAIRING)]
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self.worker = subprocess.Popen(self.command(arguments), creationflags=flags,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.start.configure(text="Stop sharing")
        self.status.configure(text="Starting...", fg=MUTED)
        self.root.after(700, self.wait_for_codes, 0)

    def wait_for_codes(self, attempt):
        if not self.worker:
            return
        if self.worker.poll() is not None:
            self.stop()
            self.status.configure(text="Could not start sharing. Check the address and port, then see server.log in "
                                  + str(STATE), fg=GOLD)
            return
        try:
            self.codes = json.loads(PAIRING.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            if attempt < 60:
                self.root.after(500, self.wait_for_codes, attempt + 1)
            return
        self.status.configure(text=f"Sharing {len(self.codes)} folder(s) on {self.address.get()}. Select a folder to show its QR.", fg=TEXT)
        if not self.folders.curselection():
            self.folders.selection_set(0)
        self.show_code()

    def restart(self):
        self.stop()
        self.toggle()

    def stop(self):
        if self.worker:
            self.worker.terminate()
            try:
                self.worker.wait(5)
            except subprocess.TimeoutExpired:
                self.worker.kill()
        self.worker, self.codes = None, {}
        self.start.configure(text="Start sharing")
        self.status.configure(text="Not sharing.", fg=MUTED)
        self.qr.configure(image="")
        self.qr.image = None
        self.copy.pack_forget()

    def selected_code(self):
        selection = self.folders.curselection()
        if not selection:
            return None
        folder = self.folders.get(selection[0])
        for shared, code in self.codes.items():
            if os.path.normcase(os.path.abspath(shared)) == os.path.normcase(os.path.abspath(folder)):
                return code
        return None

    def show_code(self):
        code = self.selected_code()
        if not code:
            return
        try:
            import qrcode
            from PIL import ImageTk
            image = qrcode.make(code, box_size=6, border=2).get_image().convert("RGB")
            self.qr.image = ImageTk.PhotoImage(image)
            self.qr.configure(image=self.qr.image)
        except ImportError:
            self.qr.configure(image="", text="QR library missing; use Copy pairing code.", fg=MUTED)
        self.copy.pack()

    def copy_code(self):
        code = self.selected_code()
        if code:
            self.root.clipboard_clear()
            self.root.clipboard_append(code)
            self.status.configure(text="Pairing code copied. Paste it in the phone app.", fg=TEXT)

    def set_autostart(self):
        try:
            if self.autostart.get():
                target = self.command(["--minimized"])
                if target[1] == "serve":
                    target = [sys.executable, "--minimized"]
                else:
                    target = [sys.executable.replace("python.exe", "pythonw.exe"), target[1], "--minimized"]
                STARTUP.parent.mkdir(parents=True, exist_ok=True)
                STARTUP.write_text("@echo off\r\nstart \"\" " + subprocess.list2cmdline(target) + "\r\n", encoding="utf-8")
            else:
                STARTUP.unlink(missing_ok=True)
        except OSError:
            messagebox.showerror(APP, "Could not change the Windows startup setting.")

    def close(self):
        self.stop()
        self.root.destroy()


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "serve":
        serve(sys.argv[2:])
        return
    root = tk.Tk()
    App(root, "--minimized" in sys.argv)
    root.mainloop()


if __name__ == "__main__":
    main()
