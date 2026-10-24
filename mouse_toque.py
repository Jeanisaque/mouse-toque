# -*- coding: utf-8 -*-
"""
Touch Cell — use o celular como mouse e teclado do PC.
Criado por Isaque Andrade e Joel Andrade.

Como usar:
  1. Rode este arquivo no PC (ou dê dois cliques em "Iniciar Touch Cell.bat").
  2. No celular, conectado ao MESMO Wi-Fi, abra o endereço que aparecer
     (ou leia o QR code que abre no navegador do PC).
  3. Pronto: a tela do celular vira touchpad.

Opções:
  --porta 9000        usa outra porta
  --novo-codigo       gera um novo código de acesso
  --sem-navegador     não abre a página do QR code no PC
  --teste             não mexe no mouse de verdade, só mostra as ações (para testes)
  --sem-atualizar     não procura versão nova ao abrir
"""

import base64
import hashlib
import json
import os
import random
import socket
import socketserver
import struct
import sys
import subprocess
import threading
import time
import urllib.request
import webbrowser

APP_NOME = "Touch Cell"
CREDITOS = "Criado por Isaque Andrade e Joel Andrade"
VERSAO = "1.5"
# De onde vêm as atualizações (repositório público no GitHub)
URL_ATUALIZACAO = os.environ.get(
    "MOUSE_TOQUE_URL", "https://raw.githubusercontent.com/Jeanisaque/mouse-toque/main/")
PORTA_PADRAO = 8765
PASTA = os.path.dirname(os.path.abspath(__file__))
ARQ_CONFIG = os.path.join(PASTA, "mouse_toque_config.json")
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


# ---------------------------------------------------------------------------
# Controle do mouse e do teclado
# ---------------------------------------------------------------------------

# Códigos de tecla do Windows (virtual keys)
VK = {
    "enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "space": 0x20,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "delete": 0x2E, "home": 0x24, "end": 0x23, "pgup": 0x21, "pgdn": 0x22,
    "ctrl": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B,
    "volup": 0xAF, "voldown": 0xAE, "mute": 0xAD,
    "playpause": 0xB3, "next": 0xB0, "prev": 0xB1,
    "f5": 0x74, "f11": 0x7A,
}
# Teclas "estendidas" precisam de uma marca especial no Windows
VK_ESTENDIDAS = {0x25, 0x26, 0x27, 0x28, 0x2E, 0x24, 0x23, 0x21, 0x22, 0x5B,
                 0xAF, 0xAE, 0xAD, 0xB3, 0xB0, 0xB1}


class ControleWindows:
    """Usa a API SendInput do próprio Windows — não precisa instalar nada."""

    def __init__(self):
        import ctypes
        from ctypes import wintypes

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                        ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                        ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("dwExtraInfo", ctypes.c_size_t)]

        class HARDWAREINPUT(ctypes.Structure):
            _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
                        ("wParamH", wintypes.WORD)]

        class _U(ctypes.Union):
            _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

        class INPUT(ctypes.Structure):
            _anonymous_ = ("u",)
            _fields_ = [("type", wintypes.DWORD), ("u", _U)]

        self.ct = ctypes
        self.MOUSEINPUT, self.KEYBDINPUT, self.INPUT = MOUSEINPUT, KEYBDINPUT, INPUT
        self.user32 = ctypes.WinDLL("user32", use_last_error=True)
        self.user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
        self.user32.SendInput.restype = wintypes.UINT
        self.nome = "Windows"

    def _enviar(self, entradas):
        arr = (self.INPUT * len(entradas))(*entradas)
        self.user32.SendInput(len(entradas), arr, self.ct.sizeof(self.INPUT))

    def _mouse(self, flags, dx=0, dy=0, dados=0):
        e = self.INPUT(type=0)
        e.mi = self.MOUSEINPUT(dx, dy, dados & 0xFFFFFFFF, flags, 0, 0)
        return e

    def _tecla(self, vk=0, scan=0, flags=0):
        e = self.INPUT(type=1)
        e.ki = self.KEYBDINPUT(vk, scan, flags, 0, 0)
        return e

    # --- mouse ---
    def mover(self, dx, dy):
        self._enviar([self._mouse(0x0001, dx, dy)])

    _BOTOES = {"l": (0x0002, 0x0004), "r": (0x0008, 0x0010), "m": (0x0020, 0x0040)}

    def apertar(self, b):
        self._enviar([self._mouse(self._BOTOES[b][0])])

    def soltar(self, b):
        self._enviar([self._mouse(self._BOTOES[b][1])])

    def clicar(self, b):
        d, u = self._BOTOES[b]
        self._enviar([self._mouse(d), self._mouse(u)])

    def rolar(self, y, x):
        if y:
            self._enviar([self._mouse(0x0800, dados=y)])   # roda vertical
        if x:
            self._enviar([self._mouse(0x1000, dados=x)])   # roda horizontal

    # --- teclado ---
    def _vk(self, codigo, soltar=False):
        flags = 0x0002 if soltar else 0
        if codigo in VK_ESTENDIDAS:
            flags |= 0x0001
        return self._tecla(vk=codigo, flags=flags)

    def tecla(self, combinacao):
        codigos = []
        for parte in combinacao.lower().split("+"):
            if parte in VK:
                codigos.append(VK[parte])
            elif len(parte) == 1 and parte.isalnum():
                codigos.append(ord(parte.upper()))
            else:
                return
        entradas = [self._vk(c) for c in codigos]
        entradas += [self._vk(c, soltar=True) for c in reversed(codigos)]
        self._enviar(entradas)

    def digitar(self, texto):
        entradas = []
        for ch in texto:
            if ch == "\n":
                entradas += [self._vk(VK["enter"]), self._vk(VK["enter"], True)]
                continue
            unidades = ch.encode("utf-16-le")
            for i in range(0, len(unidades), 2):
                u = int.from_bytes(unidades[i:i + 2], "little")
                entradas.append(self._tecla(scan=u, flags=0x0004))           # unicode
                entradas.append(self._tecla(scan=u, flags=0x0004 | 0x0002))  # soltar
        if entradas:
            self._enviar(entradas)


class ControlePynput:
    """Para Mac/Linux: precisa de 'pip install pynput'."""

    def __init__(self):
        from pynput.mouse import Controller as Mouse, Button
        from pynput.keyboard import Controller as Teclado, Key
        self.m, self.t, self.Key = Mouse(), Teclado(), Key
        self.B = {"l": Button.left, "r": Button.right, "m": Button.middle}
        self.resto_y = self.resto_x = 0
        self.nome = "pynput"
        self.mapa = {
            "enter": Key.enter, "backspace": Key.backspace, "tab": Key.tab, "esc": Key.esc,
            "space": Key.space, "left": Key.left, "up": Key.up, "right": Key.right,
            "down": Key.down, "delete": Key.delete, "home": Key.home, "end": Key.end,
            "pgup": Key.page_up, "pgdn": Key.page_down, "ctrl": Key.ctrl, "alt": Key.alt,
            "shift": Key.shift, "win": Key.cmd, "volup": Key.media_volume_up,
            "voldown": Key.media_volume_down, "mute": Key.media_volume_mute,
            "playpause": Key.media_play_pause, "next": Key.media_next,
            "prev": Key.media_previous, "f5": Key.f5, "f11": Key.f11,
        }

    def mover(self, dx, dy): self.m.move(dx, dy)
    def apertar(self, b): self.m.press(self.B[b])
    def soltar(self, b): self.m.release(self.B[b])
    def clicar(self, b): self.m.click(self.B[b])

    def rolar(self, y, x):
        self.resto_y += y
        self.resto_x += x
        py, px = int(self.resto_y / 120), int(self.resto_x / 120)
        self.resto_y -= py * 120
        self.resto_x -= px * 120
        if py or px:
            self.m.scroll(px, py)

    def tecla(self, combinacao):
        teclas = [self.mapa.get(p, p) for p in combinacao.lower().split("+")]
        for t in teclas:
            self.t.press(t)
        for t in reversed(teclas):
            self.t.release(t)

    def digitar(self, texto): self.t.type(texto)


class ControleTeste:
    """Não mexe em nada, só mostra no terminal (modo --teste)."""
    nome = "teste"

    def __init__(self): self.log = []
    def _r(self, *a):
        self.log.append(a)
        print("   [teste]", *a)
    def mover(self, dx, dy): self._r("mover", dx, dy)
    def apertar(self, b): self._r("apertar", b)
    def soltar(self, b): self._r("soltar", b)
    def clicar(self, b): self._r("clicar", b)
    def rolar(self, y, x): self._r("rolar", y, x)
    def tecla(self, c): self._r("tecla", c)
    def digitar(self, t): self._r("digitar", t)


def criar_controle(teste=False):
    if teste:
        return ControleTeste()
    if sys.platform == "win32":
        return ControleWindows()
    try:
        return ControlePynput()
    except Exception:
        print("Neste sistema é preciso instalar o pynput:  pip install pynput")
        sys.exit(1)


# ---------------------------------------------------------------------------
# Tradução das mensagens do celular em ações
# ---------------------------------------------------------------------------

def _num(v, limite):
    try:
        v = int(v)
    except (TypeError, ValueError):
        return 0
    return max(-limite, min(limite, v))


class Executor:
    def __init__(self, controle):
        self.c = controle
        self.trava = threading.Lock()
        self.apertados = set()

    def tratar(self, msg):
        t = msg.get("t")
        b = msg.get("b") if msg.get("b") in ("l", "r", "m") else "l"
        with self.trava:
            if t == "m":
                dx, dy = _num(msg.get("x"), 3000), _num(msg.get("y"), 3000)
                if dx or dy:
                    self.c.mover(dx, dy)
            elif t == "c":
                self.c.clicar(b)
            elif t == "d":
                self.apertados.add(b)
                self.c.apertar(b)
            elif t == "u":
                self.apertados.discard(b)
                self.c.soltar(b)
            elif t == "s":
                y, x = _num(msg.get("y"), 6000), _num(msg.get("x"), 6000)
                if y or x:
                    self.c.rolar(y, x)
            elif t == "txt":
                s = str(msg.get("s", ""))[:500]
                if s:
                    self.c.digitar(s)
            elif t == "k":
                nome = str(msg.get("k", ""))[:40]
                for _ in range(max(1, min(_num(msg.get("n", 1), 200), 200))):
                    self.c.tecla(nome)

    def soltar_tudo(self):
        """Se o celular desconectar no meio de um arraste, solta o botão."""
        with self.trava:
            for b in list(self.apertados):
                try:
                    self.c.soltar(b)
                except Exception:
                    pass
            self.apertados.clear()


# ---------------------------------------------------------------------------
# Brilho da tela e suspender
# ---------------------------------------------------------------------------

def _powershell(comando, limite=10):
    flags = 0x08000000 if sys.platform == "win32" else 0   # sem janela piscando
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", comando],
                       capture_output=True, text=True, timeout=limite, creationflags=flags)
    return r.returncode, (r.stdout or "").strip()


def _brilho_ddc(valor=None):
    """Monitores externos (DDC/CI). Sem valor: lê (0-100). Com valor: ajusta e devolve True/False."""
    import ctypes
    from ctypes import wintypes

    class PHYSICAL_MONITOR(ctypes.Structure):
        _fields_ = [("hPhysicalMonitor", wintypes.HANDLE),
                    ("szPhysicalMonitorDescription", wintypes.WCHAR * 128)]

    user32, dxva2 = ctypes.windll.user32, ctypes.windll.dxva2
    DW = ctypes.POINTER(wintypes.DWORD)
    dxva2.GetNumberOfPhysicalMonitorsFromHMONITOR.argtypes = [wintypes.HMONITOR, DW]
    dxva2.GetPhysicalMonitorsFromHMONITOR.argtypes = [wintypes.HMONITOR, wintypes.DWORD,
                                                       ctypes.POINTER(PHYSICAL_MONITOR)]
    dxva2.GetMonitorBrightness.argtypes = [wintypes.HANDLE, DW, DW, DW]
    dxva2.SetMonitorBrightness.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    dxva2.DestroyPhysicalMonitors.argtypes = [wintypes.DWORD, ctypes.POINTER(PHYSICAL_MONITOR)]

    monitores = []
    PROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMONITOR, wintypes.HDC,
                              ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)
    def _cb(hmon, hdc, rect, lp):
        monitores.append(hmon)
        return True
    cb = PROC(_cb)
    user32.EnumDisplayMonitors(None, None, cb, 0)

    lido, ajustou = None, False
    for hmon in monitores:
        n = wintypes.DWORD()
        if not dxva2.GetNumberOfPhysicalMonitorsFromHMONITOR(hmon, ctypes.byref(n)) or not n.value:
            continue
        lista = (PHYSICAL_MONITOR * n.value)()
        if not dxva2.GetPhysicalMonitorsFromHMONITOR(hmon, n.value, lista):
            continue
        try:
            for pm in lista:
                mn, at, mx = wintypes.DWORD(), wintypes.DWORD(), wintypes.DWORD()
                if not dxva2.GetMonitorBrightness(pm.hPhysicalMonitor, ctypes.byref(mn),
                                                  ctypes.byref(at), ctypes.byref(mx)):
                    continue
                faixa = max(1, mx.value - mn.value)
                if valor is None:
                    if lido is None:
                        lido = round((at.value - mn.value) * 100 / faixa)
                elif dxva2.SetMonitorBrightness(pm.hPhysicalMonitor, mn.value + round(valor * faixa / 100)):
                    ajustou = True
        finally:
            dxva2.DestroyPhysicalMonitors(n.value, lista)
    return lido if valor is None else ajustou


class Energia:
    """Brilho da tela e suspensão (com timer)."""

    def __init__(self, teste=False):
        self.teste = teste
        self.trava = threading.Lock()
        self.timer = None
        self.fim = 0.0
        self.avisar = lambda msg: None      # o servidor troca por uma função que avisa os celulares
        self.brilho_teste = 50

    # ---- brilho ----
    def ler_brilho(self):
        if self.teste:
            return self.brilho_teste
        if sys.platform != "win32":
            return None
        try:   # notebook / tela embutida
            cod, saida = _powershell("(Get-WmiObject -Namespace root/wmi -Class WmiMonitorBrightness "
                                     "-ErrorAction Stop | Select-Object -First 1).CurrentBrightness")
            if cod == 0 and saida.isdigit():
                return int(saida)
        except Exception:
            pass
        try:   # monitor externo
            return _brilho_ddc()
        except Exception:
            return None

    def definir_brilho(self, valor):
        valor = max(0, min(100, int(valor)))
        if self.teste:
            print(f"   [teste] brilho {valor}")
            self.brilho_teste = valor
            return valor
        if sys.platform != "win32":
            return None
        try:
            cod, _ = _powershell("(Get-WmiObject -Namespace root/wmi -Class WmiMonitorBrightnessMethods "
                                 f"-ErrorAction Stop | Select-Object -First 1).WmiSetBrightness(1, {valor})")
            if cod == 0:
                return valor
        except Exception:
            pass
        try:
            if _brilho_ddc(valor):
                return valor
        except Exception:
            pass
        return None

    # ---- suspender ----
    def restante(self):
        with self.trava:
            return max(0, int(self.fim - time.time())) if self.fim else 0

    def agendar(self, minutos):
        with self.trava:
            if self.timer:
                self.timer.cancel()
            minutos = max(0, min(600, int(minutos)))
            espera = minutos * 60 if minutos else 1.5    # "agora": dá tempo de avisar o celular
            self.fim = time.time() + espera
            self.timer = threading.Timer(espera, self._disparar)
            self.timer.daemon = True
            self.timer.start()
        if minutos:
            print(f"  .. PC vai suspender em {minutos} min")
        else:
            print("  .. Suspendendo o PC")

    def cancelar(self):
        with self.trava:
            if self.timer:
                self.timer.cancel()
            self.timer, self.fim = None, 0.0
        print("  .. Suspensão cancelada")

    def _disparar(self):
        with self.trava:
            self.timer, self.fim = None, 0.0
        self.avisar({"t": "susp", "resta": 0})
        self.suspender_agora()

    def suspender_agora(self):
        if self.teste:
            print("   [teste] suspender")
            return
        try:
            if sys.platform == "win32":
                import ctypes
                ctypes.windll.powrprof.SetSuspendState(0, 1, 0)
            elif sys.platform == "darwin":
                subprocess.run(["pmset", "sleepnow"])
            else:
                subprocess.run(["systemctl", "suspend"])
        except Exception as e:
            print("  !! Não consegui suspender:", e)


# ---------------------------------------------------------------------------
# Servidor (HTTP + WebSocket, só com a biblioteca padrão do Python)
# ---------------------------------------------------------------------------

class ConexaoWS:
    def __init__(self, sock, buffer=b""):
        self.sock, self.buf = sock, buffer
        self.trava_envio = threading.Lock()

    def _ler(self, n):
        while len(self.buf) < n:
            parte = self.sock.recv(65536)
            if not parte:
                raise ConnectionError
            self.buf += parte
        dados, self.buf = self.buf[:n], self.buf[n:]
        return dados

    def receber(self):
        b1, b2 = self._ler(2)
        op, mascarado, tam = b1 & 0x0F, b2 & 0x80, b2 & 0x7F
        if tam == 126:
            tam = struct.unpack(">H", self._ler(2))[0]
        elif tam == 127:
            tam = struct.unpack(">Q", self._ler(8))[0]
        if tam > 1_000_000:
            raise ConnectionError
        mascara = self._ler(4) if mascarado else b"\0\0\0\0"
        dados = self._ler(tam)
        if mascarado:
            dados = bytes(b ^ mascara[i % 4] for i, b in enumerate(dados))
        return op, dados

    def enviar(self, texto, op=0x1):
        dados = texto.encode("utf-8") if isinstance(texto, str) else texto
        n = len(dados)
        if n < 126:
            cab = bytes([0x80 | op, n])
        elif n < 65536:
            cab = bytes([0x80 | op, 126]) + struct.pack(">H", n)
        else:
            cab = bytes([0x80 | op, 127]) + struct.pack(">Q", n)
        with self.trava_envio:
            self.sock.sendall(cab + dados)


class Servidor(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = sys.platform != "win32"

    def __init__(self, endereco, executor, codigo, url, energia):
        super().__init__(endereco, Atendente)
        self.executor, self.codigo, self.url = executor, codigo, url
        self.energia = energia
        self.conectados = 0
        self.conexoes = set()
        self.trava = threading.Lock()
        energia.avisar = self.avisar_todos

    def avisar_todos(self, msg):
        texto = json.dumps(msg)
        with self.trava:
            alvos = list(self.conexoes)
        for ws in alvos:
            try:
                ws.enviar(texto)
            except OSError:
                pass

    def tratar_energia(self, ws, msg):
        """Roda numa thread separada: brilho pode demorar um pouco."""
        t, en = msg.get("t"), self.energia
        try:
            if t == "brilho_ler":
                ws.enviar(json.dumps({"t": "brilho", "v": en.ler_brilho()}))
            elif t == "brilho":
                v = en.definir_brilho(_num(msg.get("v"), 100))
                self.avisar_todos({"t": "brilho", "v": v})
            elif t == "suspender":
                en.agendar(_num(msg.get("min"), 600))
                self.avisar_todos({"t": "susp", "resta": en.restante(), "agora": not msg.get("min")})
            elif t == "cancelar_susp":
                en.cancelar()
                self.avisar_todos({"t": "susp", "resta": 0})
        except Exception as e:
            print("  !! Erro em brilho/energia:", e)


class Atendente(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        dados = b""
        try:
            while b"\r\n\r\n" not in dados:
                parte = sock.recv(4096)
                if not parte or len(dados) > 65536:
                    return
                dados += parte
        except OSError:
            return
        cab, _, resto = dados.partition(b"\r\n\r\n")
        linhas = cab.decode("latin-1").split("\r\n")
        partes = linhas[0].split(" ")
        if len(partes) < 2:
            return
        rota = partes[1].split("?")[0]
        cabecalhos = {}
        for l in linhas[1:]:
            if ":" in l:
                k, v = l.split(":", 1)
                cabecalhos[k.strip().lower()] = v.strip()
        local = self.client_address[0] in ("127.0.0.1", "::1")

        if rota == "/ws" and cabecalhos.get("upgrade", "").lower() == "websocket":
            self.websocket(sock, cabecalhos, resto)
        elif rota == "/":
            self.responder(200, "text/html; charset=utf-8", PAGINA_CELULAR.replace("{{VERSAO}}", VERSAO))
        elif rota == "/pc" and local:
            pagina = (PAGINA_PC.replace("{{URL}}", self.server.url)
                      .replace("{{CODIGO}}", self.server.codigo))
            self.responder(200, "text/html; charset=utf-8", pagina)
        elif rota == "/status" and local:
            self.responder(200, "application/json",
                           json.dumps({"conectados": self.server.conectados}))
        else:
            self.responder(404, "text/plain; charset=utf-8", "Não encontrado")

    def responder(self, cod, tipo, corpo):
        corpo = corpo.encode("utf-8")
        status = {200: "OK", 404: "Not Found"}[cod]
        cab = (f"HTTP/1.1 {cod} {status}\r\nContent-Type: {tipo}\r\n"
               f"Content-Length: {len(corpo)}\r\nCache-Control: no-store\r\n"
               f"Connection: close\r\n\r\n").encode()
        try:
            self.request.sendall(cab + corpo)
        except OSError:
            pass

    def websocket(self, sock, cabecalhos, resto):
        chave = cabecalhos.get("sec-websocket-key", "")
        aceite = base64.b64encode(hashlib.sha1((chave + WS_GUID).encode()).digest()).decode()
        sock.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                      "Connection: Upgrade\r\n"
                      f"Sec-WebSocket-Accept: {aceite}\r\n\r\n").encode())
        ws = ConexaoWS(sock, resto)
        srv = self.server
        autorizado = False
        ip = self.client_address[0]
        try:
            while True:
                op, dados = ws.receber()
                if op == 0x8:          # fechar
                    break
                if op == 0x9:          # ping
                    ws.enviar(dados, op=0xA)
                    continue
                if op != 0x1:
                    continue
                try:
                    msg = json.loads(dados.decode("utf-8"))
                except ValueError:
                    continue
                if not autorizado:
                    if msg.get("t") == "auth" and str(msg.get("k", "")) == srv.codigo:
                        autorizado = True
                        with srv.trava:
                            srv.conectados += 1
                        with srv.trava:
                            srv.conexoes.add(ws)
                        ws.enviar(json.dumps({"t": "ok", "pc": socket.gethostname(),
                                              "susp": srv.energia.restante()}))
                        print(f"  >> Celular conectado ({ip})")
                    else:
                        ws.enviar(json.dumps({"t": "erro"}))
                        print(f"  !! Tentativa com código errado ({ip})")
                        break
                    continue
                if msg.get("t") == "ping":
                    ws.enviar('{"t":"pong"}')
                    continue
                if msg.get("t") in ("brilho_ler", "brilho", "suspender", "cancelar_susp"):
                    threading.Thread(target=srv.tratar_energia, args=(ws, msg), daemon=True).start()
                    continue
                try:
                    srv.executor.tratar(msg)
                except Exception as e:
                    print("  !! Erro ao executar ação:", e)
        except (ConnectionError, OSError):
            pass
        finally:
            if autorizado:
                srv.executor.soltar_tudo()
                with srv.trava:
                    srv.conectados -= 1
                    srv.conexoes.discard(ws)
                print(f"  << Celular desconectado ({ip})")


# ---------------------------------------------------------------------------
# Início
# ---------------------------------------------------------------------------

def ip_local():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def carregar_codigo(novo=False):
    cfg = {}
    try:
        with open(ARQ_CONFIG, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError):
        pass
    codigo = str(cfg.get("codigo", ""))
    if novo or not (codigo.isdigit() and len(codigo) == 4):
        codigo = f"{random.SystemRandom().randint(0, 9999):04d}"
        cfg["codigo"] = codigo
        try:
            with open(ARQ_CONFIG, "w", encoding="utf-8") as f:
                json.dump(cfg, f)
        except OSError:
            pass
    return codigo


# ---------------------------------------------------------------------------
# Atualização automática
# ---------------------------------------------------------------------------

def _versao_tupla(v):
    try:
        return tuple(int(x) for x in str(v).strip().split("."))
    except ValueError:
        return (0,)


def _baixar(nome, limite=4):
    url = URL_ATUALIZACAO + nome + "?t=" + str(int(time.time()))
    req = urllib.request.Request(url, headers={"User-Agent": "TouchCell/" + VERSAO,
                                               "Cache-Control": "no-cache"})
    with urllib.request.urlopen(req, timeout=limite) as r:
        return r.read()


def verificar_atualizacao(argv):
    """Procura versão nova no GitHub e, se houver, instala sozinho e reinicia."""
    print("  Procurando atualizações...", end=" ", flush=True)
    try:
        info = json.loads(_baixar("versao.json").decode("utf-8"))
        nova = str(info.get("versao", "0"))
    except Exception:
        print("sem conexão, seguindo com a versão atual.")
        return False
    if _versao_tupla(nova) <= _versao_tupla(VERSAO):
        print(f"você já tem a versão mais nova ({VERSAO}).")
        return False

    print()
    print(f"  >>> Atualizando da versão {VERSAO} para a {nova}...")
    for linha in info.get("novidades", []):
        print(f"      - {linha}")

    try:
        codigo = _baixar("mouse_toque.py", limite=20)
        texto = codigo.decode("utf-8")
        if "APP_NOME" not in texto:
            raise ValueError("arquivo inesperado")
        compile(texto, "mouse_toque.py", "exec")   # confere se não veio corrompido
    except Exception as e:
        print(f"  Não consegui baixar a atualização ({e}). Seguindo com a atual.\n")
        return False

    atual = os.path.abspath(__file__)
    backup = os.path.join(PASTA, "mouse_toque_anterior.py")
    try:
        with open(atual, "rb") as f:
            antigo = f.read()
        with open(backup, "wb") as f:
            f.write(antigo)
        with open(atual, "wb") as f:
            f.write(codigo)
    except OSError as e:
        print(f"  Não consegui salvar a atualização ({e}). Seguindo com a atual.\n")
        return False

    print(f"  Atualizado para a versão {nova}! Reiniciando...\n")
    resultado = subprocess.call([sys.executable, atual, "--sem-atualizar"] + argv)
    sys.exit(resultado)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--sem-atualizar" not in argv and "--teste" not in argv:
        verificar_atualizacao(argv)
    teste = "--teste" in argv
    porta = PORTA_PADRAO
    if "--porta" in argv:
        try:
            porta = int(argv[argv.index("--porta") + 1])
        except (IndexError, ValueError):
            pass

    codigo = carregar_codigo("--novo-codigo" in argv)
    executor = Executor(criar_controle(teste))
    energia = Energia(teste)
    ip = ip_local()

    servidor = None
    for p in range(porta, porta + 10):
        try:
            url = f"http://{ip}:{p}/?k={codigo}"
            servidor = Servidor(("0.0.0.0", p), executor, codigo, url, energia)
            porta = p
            break
        except OSError:
            continue
    if servidor is None:
        print("Não consegui abrir nenhuma porta. Feche outros programas e tente de novo.")
        sys.exit(1)

    linha = "=" * 58
    print(linha)
    print(f"  {APP_NOME} v{VERSAO}")
    print(f"  {CREDITOS}")
    print(linha)
    print()
    print("  No celular (mesmo Wi-Fi), abra no navegador:")
    print()
    print(f"      {url}")
    print()
    print(f"  Código de acesso: {codigo}")
    print()
    print("  Se o Windows perguntar sobre o Firewall, marque")
    print("  'Redes privadas' e clique em Permitir.")
    print()
    print("  Para encerrar, feche esta janela ou aperte Ctrl+C.")
    print(linha)

    if "--sem-navegador" not in argv:
        threading.Timer(0.8, lambda: webbrowser.open(f"http://127.0.0.1:{porta}/pc")).start()

    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\n  Encerrado. Até mais!")
    finally:
        servidor.server_close()


# ---------------------------------------------------------------------------
# Página do celular (o touchpad)
# ---------------------------------------------------------------------------

PAGINA_CELULAR = r"""<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content">
<meta name="theme-color" content="#0f1115">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>Touch Cell</title>
<style>
:root{--bg:#0f1115;--pad:#171a21;--pad2:#1f232c;--borda:#2b303b;--txt:#e9ebf1;--sub:#8b93a4;--acc:#4f8cff;--ok:#2ecc71;--err:#ff5a5a;--warn:#f5b041}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;height:100%;background:var(--bg);color:var(--txt);font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;overscroll-behavior:none;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
body{display:flex;flex-direction:column;height:100vh;height:100dvh;padding:env(safe-area-inset-top) 12px calc(env(safe-area-inset-bottom) + 6px);overflow:hidden}
header{display:flex;align-items:center;gap:10px;padding:10px 2px 8px}
header h1{font-size:17px;margin:0;font-weight:650;flex:1;letter-spacing:.2px}
.status{display:flex;align-items:center;gap:6px;font-size:12.5px;color:var(--sub);max-width:45%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dot{width:9px;height:9px;border-radius:50%;background:var(--warn);flex:none}
.dot.ok{background:var(--ok)}.dot.off{background:var(--err)}
.icone{background:none;border:0;color:var(--txt);font-size:21px;padding:2px 4px;line-height:1}
#sincronizar{font-size:23px;padding:2px 8px}
#sincronizar.girando{animation:girar .7s linear}
@keyframes girar{to{transform:rotate(360deg)}}
#pad{flex:1;min-height:140px;background:var(--pad);border:1px solid var(--borda);border-radius:18px;position:relative;touch-action:none;overflow:hidden}
#pad.ativo{border-color:#3a4252}
#pad.com-foto #dica{color:#fff;text-shadow:0 1px 4px rgba(0,0,0,.95)}
#pad.com-foto #dica b{color:#fff}
.cartao{max-height:92vh;max-height:92dvh;overflow-y:auto}
#fundo{position:absolute;inset:0;background-size:cover;background-position:center;pointer-events:none;display:none}
.mini{background:var(--pad2);border:1px solid var(--borda);border-radius:10px;padding:7px 12px;font-size:13.5px;color:var(--txt)}
.mini.perigo{color:var(--err)}
.acoes{display:flex;gap:8px}
#msg-foto{color:var(--sub);font-size:12.5px;padding:0 0 6px}
.brilho-ctl{display:flex;align-items:center;gap:10px;padding:4px 0 8px}
.brilho-ctl input{flex:1;accent-color:var(--acc);height:28px}
.mini.passo{width:44px;height:44px;padding:0;font-size:20px}
#msg-brilho{color:var(--sub);font-size:12.5px;line-height:1.45;min-height:4px;padding-bottom:6px}
.subtitulo{margin:14px 0 4px;font-size:15px}
.dica-susp{margin:0 0 10px;color:var(--sub);font-size:12.5px;line-height:1.45}
.susp-grade{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}
.susp-grade .mini{height:44px;padding:0}
.mini.confirmar{background:var(--err);border-color:var(--err);color:#fff}
#susp-status{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:12px;padding:10px 12px;border-radius:12px;background:#2a2412;border:1px solid #5c4a17;font-size:14px}
#susp-status[hidden]{display:none}
#susp-mini{color:var(--warn);font-size:12px}
#dica{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:7px;color:var(--sub);font-size:13px;text-align:center;pointer-events:none;transition:opacity .8s;padding:20px;line-height:1.45}
#dica b{color:var(--txt);font-size:15px;font-weight:600;margin-bottom:4px}
#dica.some{opacity:0}
.botoes{display:grid;grid-template-columns:1fr .5fr 1fr;gap:8px;margin-top:8px}
button{font:inherit;color:var(--txt)}
.botoes button,.teclas button,.barra button{background:var(--pad2);border:1px solid var(--borda);border-radius:14px;touch-action:none}
.botoes button{height:60px;font-size:14px;color:var(--sub)}
.botoes button.ativo,.teclas button.ativo,.barra button.ativo{background:var(--acc);border-color:var(--acc);color:#fff}
.barra{display:flex;gap:8px;margin-top:8px}
.barra button{flex:1;height:42px;font-size:14px}
#teclado{display:none;margin-top:8px}
#teclado.aberto{display:block}
#digitar{width:100%;height:46px;border-radius:12px;border:1px solid var(--borda);background:var(--pad);color:var(--txt);font-size:16px;padding:0 14px;outline:none;-webkit-user-select:text;user-select:text}
#digitar:focus{border-color:var(--acc)}
.teclas{display:grid;grid-template-columns:repeat(5,1fr);gap:6px;margin-top:8px}
.teclas button{height:40px;font-size:13px;padding:0}
footer{text-align:center;font-size:11px;color:var(--sub);padding:7px 0 0;opacity:.85}
.folha{position:fixed;inset:0;background:rgba(5,7,10,.72);display:none;align-items:flex-end;justify-content:center;z-index:10}
.folha.aberta{display:flex}
.cartao{background:#181b22;border:1px solid var(--borda);border-radius:20px 20px 0 0;width:100%;max-width:520px;padding:20px 18px calc(env(safe-area-inset-bottom) + 20px)}
.cartao h2{margin:0 0 14px;font-size:17px}
.linha{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid #22262f;font-size:14.5px}
.linha:last-of-type{border-bottom:0}
.linha input[type=range]{width:46%;accent-color:var(--acc)}
.linha input[type=checkbox]{width:22px;height:22px;accent-color:var(--acc)}
.valor{color:var(--sub);font-size:12.5px;min-width:30px;text-align:right}
.ok-btn{width:100%;height:48px;border-radius:14px;border:0;background:var(--acc);color:#fff;font-size:15px;font-weight:600;margin-top:14px}
.creditos{margin-top:14px;text-align:center;color:var(--sub);font-size:12.5px;line-height:1.5}
#cod-input{width:100%;height:56px;font-size:28px;text-align:center;letter-spacing:12px;border-radius:14px;border:1px solid var(--borda);background:var(--pad);color:var(--txt);outline:none;-webkit-user-select:text;user-select:text}
#cod-msg{color:var(--sub);font-size:13.5px;margin:0 0 12px;line-height:1.45}
#cod-msg.erro{color:var(--err)}
body.digitando header,body.digitando footer,body.digitando .botoes{display:none}
body.digitando #pad{min-height:56px}
body.digitando #dica{display:none}
body.digitando .barra{margin-top:6px}
body.digitando .barra button{height:34px;font-size:13px}
body.digitando #teclado{margin-top:6px}
body.digitando #digitar{height:40px}
body.digitando .teclas{gap:5px;margin-top:6px}
body.digitando .teclas button{height:34px;font-size:12.5px}
</style>
</head>
<body>
<header>
  <h1>Touch Cell</h1>
  <div class="status"><span class="dot" id="dot"></span><span id="status-txt">Conectando…</span></div>
  <button class="icone" id="sincronizar" aria-label="Sincronizar">⟳</button>
  <button class="icone" id="abrir-config" aria-label="Ajustes">⚙︎</button>
</header>

<div id="pad">
  <div id="fundo"></div>
  <div id="dica">
    <b>Deslize para mover o mouse</b>
    Toque = clique &nbsp;•&nbsp; 2 dedos tocando = clique direito<br>
    2 dedos deslizando = rolar<br>
    Toque duplo e segure = arrastar
  </div>
</div>

<div class="botoes">
  <button data-b="l">Esquerdo</button>
  <button data-b="m">Meio</button>
  <button data-b="r">Direito</button>
</div>

<div class="barra">
  <button id="btn-teclado">⌨︎ Teclado</button>
  <button id="btn-energia">☀︎ Brilho e energia <span id="susp-mini"></span></button>
</div>

<div id="teclado">
  <input id="digitar" type="text" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" enterkeyhint="send" placeholder="Toque aqui e digite no PC">
  <div class="teclas">
    <button data-k="esc">Esc</button>
    <button data-k="tab">Tab</button>
    <button data-k="up">↑</button>
    <button data-k="backspace">⌫</button>
    <button data-k="enter">Enter</button>
    <button data-k="ctrl+c">Copiar</button>
    <button data-k="left">←</button>
    <button data-k="down">↓</button>
    <button data-k="right">→</button>
    <button data-k="ctrl+v">Colar</button>
    <button data-k="ctrl+z">Desfazer</button>
    <button data-k="alt+tab">Alt+Tab</button>
    <button data-k="win">Iniciar</button>
    <button data-k="delete">Del</button>
    <button data-k="f11">Tela cheia</button>
    <button data-k="voldown">Vol −</button>
    <button data-k="mute">Mudo</button>
    <button data-k="volup">Vol +</button>
    <button data-k="playpause">⏯︎</button>
    <button data-k="next">⏭︎</button>
  </div>
</div>

<footer>Criado por Isaque Andrade e Joel Andrade</footer>

<div class="folha" id="config">
  <div class="cartao">
    <h2>Ajustes</h2>
    <div class="linha"><span>Velocidade do mouse</span><input type="range" id="c-sens" min="0.5" max="4" step="0.1"><span class="valor" id="v-sens"></span></div>
    <div class="linha"><span>Aceleração</span><input type="checkbox" id="c-acel"></div>
    <div class="linha"><span>Velocidade da rolagem</span><input type="range" id="c-rol" min="0.5" max="8" step="0.5"><span class="valor" id="v-rol"></span></div>
    <div class="linha"><span>Inverter rolagem</span><input type="checkbox" id="c-inv"></div>
    <div class="linha"><span>Tocar para clicar</span><input type="checkbox" id="c-toque"></div>
    <div class="linha"><span>Vibrar ao clicar</span><input type="checkbox" id="c-vib"></div>
    <div class="linha"><span>Foto de fundo</span><span class="acoes"><button class="mini" id="foto-escolher">Escolher</button><button class="mini perigo" id="foto-remover">Remover</button></span></div>
    <div id="msg-foto"></div>
    <div class="linha"><span>Transparência da foto</span><input type="range" id="c-transp" min="0" max="95" step="5"><span class="valor" id="v-transp"></span></div>
    <input type="file" id="foto-arquivo" accept="image/*" hidden>
    <button class="ok-btn" id="fechar-config">Pronto</button>
    <div class="creditos">Touch Cell {{VERSAO}}<br>Criado por <b>Isaque Andrade</b> e <b>Joel Andrade</b></div>
  </div>
</div>

<div class="folha" id="energia">
  <div class="cartao">
    <h2>Brilho e energia</h2>
    <div class="linha"><span>Brilho da tela</span><span class="valor" id="v-brilho">…</span></div>
    <div class="brilho-ctl">
      <button class="mini passo" id="brilho-menos" aria-label="Diminuir brilho">−</button>
      <input type="range" id="e-brilho" min="0" max="100" step="5" value="50" aria-label="Brilho da tela">
      <button class="mini passo" id="brilho-mais" aria-label="Aumentar brilho">+</button>
    </div>
    <div id="msg-brilho"></div>
    <h3 class="subtitulo">Suspender o PC</h3>
    <p class="dica-susp">O PC entra em repouso e acorda ao mexer no mouse ou no teclado dele.</p>
    <div class="susp-grade">
      <button class="mini susp" data-min="0" id="susp-agora">Agora</button>
      <button class="mini susp" data-min="15">15 min</button>
      <button class="mini susp" data-min="30">30 min</button>
      <button class="mini susp" data-min="60">1 hora</button>
    </div>
    <div id="susp-status" hidden>
      <span id="susp-texto"></span>
      <button class="mini perigo" id="susp-cancelar">Cancelar</button>
    </div>
    <button class="ok-btn" id="fechar-energia">Pronto</button>
  </div>
</div>

<div class="folha" id="codigo">
  <div class="cartao">
    <h2>Código de acesso</h2>
    <p id="cod-msg">Digite o código de 4 números que aparece na tela do PC.</p>
    <input id="cod-input" type="tel" inputmode="numeric" maxlength="4" autocomplete="off">
    <button class="ok-btn" id="cod-ok">Conectar</button>
  </div>
</div>

<script>
(function(){
"use strict";

// ---------- armazenamento (opcional; funciona mesmo se falhar) ----------
function ler(k, padrao){ try{ const v = localStorage.getItem("mt_"+k); return v===null ? padrao : JSON.parse(v); }catch(e){ return padrao; } }
function gravar(k, v){ try{ localStorage.setItem("mt_"+k, JSON.stringify(v)); }catch(e){} }

const cfg = Object.assign({sens:1.6, acel:true, rol:3, inv:false, toque:true, vib:true, transp:40}, ler("cfg", {}));

// ---------- código de acesso ----------
const params = new URLSearchParams(location.search);
let codigo = params.get("k") || ler("codigo", "");
if (params.get("k")) gravar("codigo", codigo);

// ---------- conexão ----------
const dot = document.getElementById("dot"), statusTxt = document.getElementById("status-txt");
let ws = null, pronto = false, bloqueado = false, timerReconexao = null, ultimaResposta = 0;

function status(tipo, texto){ dot.className = "dot " + tipo; statusTxt.textContent = texto; }

function conectar(){
  if (bloqueado) return;
  if (!codigo){ pedirCodigo(); return; }
  clearTimeout(timerReconexao);
  if (ws && ws.readyState === 0) return;   // já está tentando
  status("", "Conectando…");
  let este;
  try { este = new WebSocket("ws://" + location.host + "/ws"); } catch(e){ agendar(); return; }
  ws = este;
  setTimeout(function(){ if (este === ws && este.readyState === 0) reconectar(); }, 5000);  // demorou demais
  este.onopen = function(){ if (este === ws) este.send(JSON.stringify({t:"auth", k:codigo})); };
  este.onmessage = function(e){
    if (este !== ws) return;
    let m; try{ m = JSON.parse(e.data); }catch(_){ return; }
    ultimaResposta = Date.now();
    if (m.t === "ok"){ pronto = true; status("ok", m.pc ? "Conectado a " + m.pc : "Conectado"); if (window.mtSusp) window.mtSusp(m.susp || 0); }
    else if (m.t === "brilho"){ if (window.mtBrilho) window.mtBrilho(m.v); }
    else if (m.t === "susp"){ if (window.mtSusp) window.mtSusp(m.resta || 0, m.agora); }
    else if (m.t === "erro"){ pronto = false; bloqueado = true; gravar("codigo", ""); codigo = ""; pedirCodigo("Código incorreto. Confira na tela do PC."); }
  };
  este.onclose = function(){
    if (este !== ws) return;
    pronto = false;
    if (!bloqueado){ status("off", "Desconectado — tentando de novo…"); agendar(); }
  };
  este.onerror = function(){ try{ este.close(); }catch(_){} };
}
function agendar(){ clearTimeout(timerReconexao); timerReconexao = setTimeout(conectar, 1500); }

// Fecha a conexão atual (mesmo que pareça aberta) e abre uma nova
function reconectar(){
  const velho = ws; ws = null; pronto = false;
  if (velho){ velho.onclose = velho.onerror = velho.onmessage = velho.onopen = null; try{ velho.close(); }catch(e){} }
  conectar();
}

function enviar(o){
  if (pronto && ws && ws.readyState === 1) ws.send(JSON.stringify(o));
  else if (!bloqueado && (!ws || ws.readyState > 1)) conectar();
}

// Ao voltar para a página, sempre reconecta: o celular costuma derrubar
// a conexão em segundo plano sem avisar.
document.addEventListener("visibilitychange", function(){ if (!document.hidden) reconectar(); });
window.addEventListener("pageshow", function(e){ if (e.persisted) reconectar(); });
window.addEventListener("online", reconectar);

// Batimento: se o PC parar de responder, reconecta sozinho
setInterval(function(){
  if (document.hidden || !pronto || !ws || ws.readyState !== 1) return;
  if (Date.now() - ultimaResposta > 7000){ status("off", "Sem resposta — reconectando…"); reconectar(); return; }
  try{ ws.send('{"t":"ping"}'); }catch(e){ reconectar(); }
}, 2500);

function vibrar(ms){ if (cfg.vib && navigator.vibrate) try{ navigator.vibrate(ms); }catch(e){} }

// ---------- envio agrupado (uma mensagem por quadro de tela) ----------
let mx=0, my=0, rx=0, ry=0, sx=0, sy=0, srx=0, sry=0, agendado=false;
function agendarEnvio(){ if (!agendado){ agendado = true; requestAnimationFrame(despachar); } }
function despachar(){
  agendado = false;
  let x = mx + rx, y = my + ry; mx = my = 0;
  const ix = Math.trunc(x), iy = Math.trunc(y); rx = x - ix; ry = y - iy;
  if (ix || iy) enviar({t:"m", x:ix, y:iy});
  let a = sx + srx, b = sy + sry; sx = sy = 0;
  const ia = Math.trunc(a), ib = Math.trunc(b); srx = a - ia; sry = b - ib;
  if (ia || ib) enviar({t:"s", x:ia, y:ib});
}

// ---------- touchpad ----------
const pad = document.getElementById("pad"), dica = document.getElementById("dica");
let gesto = null, ultimaPos = null, ultimoT = 0, dedosAntes = 0, ultimoToqueEsq = 0;

function media(lista){
  let x = 0, y = 0;
  for (let i = 0; i < lista.length; i++){ x += lista[i].clientX; y += lista[i].clientY; }
  return {x: x / lista.length, y: y / lista.length};
}

pad.addEventListener("touchstart", function(e){
  e.preventDefault();
  const agora = performance.now(), n = e.touches.length;
  if (!gesto){
    gesto = {inicio:agora, maxDedos:n, moveu:0, arrastando:false,
             podeArrastar: n === 1 && (agora - ultimoToqueEsq) < 300};
    dica.classList.add("some");
    pad.classList.add("ativo");
  } else {
    gesto.maxDedos = Math.max(gesto.maxDedos, n);
  }
  ultimaPos = media(e.touches); ultimoT = agora; dedosAntes = n;
}, {passive:false});

pad.addEventListener("touchmove", function(e){
  e.preventDefault();
  if (!gesto) return;
  const agora = performance.now(), n = e.touches.length, p = media(e.touches);
  if (n !== dedosAntes){ ultimaPos = p; dedosAntes = n; ultimoT = agora; return; }
  const dx = p.x - ultimaPos.x, dy = p.y - ultimaPos.y, dt = Math.max(1, agora - ultimoT);
  ultimaPos = p; ultimoT = agora;
  const dist = Math.hypot(dx, dy);
  gesto.moveu += dist;
  if (n === 1 && gesto.maxDedos === 1){
    if (gesto.podeArrastar && !gesto.arrastando && gesto.moveu > 6){
      gesto.arrastando = true; enviar({t:"d", b:"l"}); vibrar(15);
    }
    const vel = dist / dt;
    const f = cfg.sens * (cfg.acel ? Math.min(1 + vel * 0.9, 4) : 1);
    mx += dx * f; my += dy * f; agendarEnvio();
  } else if (n === 2){
    const k = cfg.rol * (cfg.inv ? -1 : 1);
    sy += dy * k; sx += -dx * k; agendarEnvio();
  }
}, {passive:false});

function fimDoToque(e){
  e.preventDefault();
  if (!gesto) return;
  if (e.touches.length > 0){ ultimaPos = media(e.touches); dedosAntes = e.touches.length; return; }
  const agora = performance.now(), dur = agora - gesto.inicio;
  let foiToqueEsq = false;
  if (gesto.arrastando){
    enviar({t:"u", b:"l"});
  } else if (e.type !== "touchcancel" && cfg.toque && gesto.moveu < 12 && dur < 300){
    const b = gesto.maxDedos === 1 ? "l" : (gesto.maxDedos === 2 ? "r" : "m");
    enviar({t:"c", b:b}); vibrar(10);
    foiToqueEsq = (b === "l");
  }
  ultimoToqueEsq = foiToqueEsq ? agora : 0;
  gesto = null;
  pad.classList.remove("ativo");
}
pad.addEventListener("touchend", fimDoToque, {passive:false});
pad.addEventListener("touchcancel", fimDoToque, {passive:false});

// ---------- botões do mouse (segurar = manter apertado) ----------
document.querySelectorAll(".botoes button").forEach(function(btn){
  const b = btn.dataset.b;
  let apertado = false;
  function soltar(){ if (apertado){ apertado = false; btn.classList.remove("ativo"); enviar({t:"u", b:b}); } }
  btn.addEventListener("pointerdown", function(e){ e.preventDefault(); apertado = true; btn.classList.add("ativo"); enviar({t:"d", b:b}); vibrar(10); });
  btn.addEventListener("pointerup", soltar);
  btn.addEventListener("pointercancel", soltar);
  btn.addEventListener("lostpointercapture", soltar);
  btn.addEventListener("contextmenu", function(e){ e.preventDefault(); });
});

// ---------- teclado ----------
const painel = document.getElementById("teclado"), btnTeclado = document.getElementById("btn-teclado");
const campo = document.getElementById("digitar");
const SENTINELA = "\u200B";   // caractere invisível para detectar o apagar com o campo vazio
let anterior = SENTINELA;
campo.value = SENTINELA;

btnTeclado.addEventListener("click", function(){
  const abrir = !painel.classList.contains("aberto");
  painel.classList.toggle("aberto", abrir);
  btnTeclado.classList.toggle("ativo", abrir);
  if (abrir) setTimeout(function(){ campo.focus(); }, 50); else campo.blur();
});

// ---------- ajuste da tela quando o teclado do celular abre ----------
let alturaCheia = 0, larguraBase = 0;
function ajustarTela(){
  const vv = window.visualViewport;
  if (window.innerWidth !== larguraBase){ larguraBase = window.innerWidth; alturaCheia = 0; }  // girou a tela
  const h = vv ? vv.height : window.innerHeight;
  alturaCheia = Math.max(alturaCheia, h, window.innerHeight);
  const tecladoAberto = document.activeElement === campo && h < alturaCheia * 0.8;
  document.body.classList.toggle("digitando", tecladoAberto);
  document.body.style.height = h + "px";
  window.scrollTo(0, 0);
}
if (window.visualViewport){
  window.visualViewport.addEventListener("resize", ajustarTela);
  window.visualViewport.addEventListener("scroll", ajustarTela);
}
window.addEventListener("resize", ajustarTela);
window.addEventListener("orientationchange", function(){ setTimeout(ajustarTela, 300); });
campo.addEventListener("focus", function(){ setTimeout(ajustarTela, 100); setTimeout(ajustarTela, 400); });
campo.addEventListener("blur", function(){ setTimeout(ajustarTela, 100); });
ajustarTela();

function reiniciarCampo(){ campo.value = SENTINELA; anterior = SENTINELA; }

campo.addEventListener("input", function(){
  const novo = campo.value;
  let p = 0;
  while (p < anterior.length && p < novo.length && anterior[p] === novo[p]) p++;
  const apagar = anterior.length - p, inserir = novo.slice(p).replace(/\u200B/g, "");
  if (apagar > 0) enviar({t:"k", k:"backspace", n:apagar});
  if (inserir) enviar({t:"txt", s:inserir});
  anterior = novo;
  if (!novo.startsWith(SENTINELA)) reiniciarCampo();
});
campo.addEventListener("keydown", function(e){
  if (e.key === "Enter"){ e.preventDefault(); enviar({t:"k", k:"enter"}); reiniciarCampo(); }
});

document.querySelectorAll(".teclas button").forEach(function(btn){
  btn.addEventListener("pointerdown", function(e){
    e.preventDefault();
    btn.classList.add("ativo"); vibrar(8);
    enviar({t:"k", k:btn.dataset.k});
  });
  const tirar = function(){ btn.classList.remove("ativo"); };
  btn.addEventListener("pointerup", tirar);
  btn.addEventListener("pointercancel", tirar);
  btn.addEventListener("pointerleave", tirar);
});

// ---------- ajustes ----------
const folhaCfg = document.getElementById("config");
const el = function(id){ return document.getElementById(id); };
function mostrarValores(){ el("v-sens").textContent = cfg.sens.toFixed(1) + "×"; el("v-rol").textContent = cfg.rol.toFixed(1) + "×"; el("v-transp").textContent = cfg.transp + "%"; }
el("c-sens").value = cfg.sens; el("c-acel").checked = cfg.acel; el("c-rol").value = cfg.rol;
el("c-inv").checked = cfg.inv; el("c-toque").checked = cfg.toque; el("c-vib").checked = cfg.vib; el("c-transp").value = cfg.transp;
mostrarValores();
function salvarCfg(){
  cfg.sens = parseFloat(el("c-sens").value); cfg.acel = el("c-acel").checked;
  cfg.rol = parseFloat(el("c-rol").value); cfg.inv = el("c-inv").checked;
  cfg.toque = el("c-toque").checked; cfg.vib = el("c-vib").checked;
  cfg.transp = parseInt(el("c-transp").value, 10) || 0;
  mostrarValores(); gravar("cfg", cfg); aplicarFundo();
}
["c-sens","c-acel","c-rol","c-inv","c-toque","c-vib","c-transp"].forEach(function(id){ el(id).addEventListener("input", salvarCfg); el(id).addEventListener("change", salvarCfg); });
el("abrir-config").addEventListener("click", function(){ folhaCfg.classList.add("aberta"); });
el("fechar-config").addEventListener("click", function(){ folhaCfg.classList.remove("aberta"); });
folhaCfg.addEventListener("click", function(e){ if (e.target === folhaCfg) folhaCfg.classList.remove("aberta"); });

// ---------- foto de fundo do touchpad ----------
const fundo = el("fundo"), msgFoto = el("msg-foto");
let fotoAtual = ler("foto", "");
function aplicarFundo(){
  if (fotoAtual){
    fundo.style.backgroundImage = 'url("' + fotoAtual + '")';
    fundo.style.opacity = String(1 - cfg.transp / 100);
    fundo.style.display = "block";
    pad.classList.add("com-foto");
  } else {
    fundo.style.backgroundImage = ""; fundo.style.display = "none";
    pad.classList.remove("com-foto");
  }
  el("foto-remover").style.display = fotoAtual ? "" : "none";
}
function salvarFoto(dados){
  try{ localStorage.setItem("mt_foto", JSON.stringify(dados)); return true; }catch(e){ return false; }
}
function reduzirFoto(img, lado, qualidade){
  const esc = Math.min(1, lado / Math.max(img.naturalWidth, img.naturalHeight));
  const c = document.createElement("canvas");
  c.width = Math.round(img.naturalWidth * esc); c.height = Math.round(img.naturalHeight * esc);
  c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
  return c.toDataURL("image/jpeg", qualidade);
}
el("foto-escolher").addEventListener("click", function(){ el("foto-arquivo").click(); });
el("foto-arquivo").addEventListener("change", function(){
  const arq = this.files && this.files[0]; this.value = "";
  if (!arq) return;
  msgFoto.textContent = "Carregando foto…";
  const url = URL.createObjectURL(arq), img = new Image();
  img.onload = function(){
    URL.revokeObjectURL(url);
    let dados = reduzirFoto(img, 1280, 0.82);
    let ok = salvarFoto(dados);
    if (!ok){ dados = reduzirFoto(img, 800, 0.65); ok = salvarFoto(dados); }
    fotoAtual = dados; aplicarFundo();
    msgFoto.textContent = ok ? "" : "A foto aparece agora, mas o navegador não deixou guardar para a próxima vez.";
  };
  img.onerror = function(){ URL.revokeObjectURL(url); msgFoto.textContent = "Não consegui abrir essa imagem."; };
  img.src = url;
});
el("foto-remover").addEventListener("click", function(){
  fotoAtual = ""; try{ localStorage.removeItem("mt_foto"); }catch(e){}
  msgFoto.textContent = ""; aplicarFundo();
});
aplicarFundo();

// ---------- brilho e energia ----------
const folhaEn = el("energia"), slBrilho = el("e-brilho"), vBrilho = el("v-brilho"), msgBrilho = el("msg-brilho");
let timerBrilho = null;
function mandarBrilho(v){
  v = Math.max(0, Math.min(100, Math.round(v)));
  slBrilho.value = v; vBrilho.textContent = v + "%";
  clearTimeout(timerBrilho);
  timerBrilho = setTimeout(function(){ enviar({t:"brilho", v:v}); }, 250);
}
window.mtBrilho = function(v){
  if (v === null || v === undefined){
    vBrilho.textContent = "—";
    msgBrilho.textContent = "Este monitor não deixa o computador mudar o brilho. Em monitor de mesa, ative a opção DDC/CI no menu do próprio monitor.";
    return;
  }
  msgBrilho.textContent = "";
  slBrilho.value = v; vBrilho.textContent = v + "%";
};
slBrilho.addEventListener("input", function(){ mandarBrilho(parseInt(slBrilho.value, 10)); });
el("brilho-menos").addEventListener("click", function(){ vibrar(8); mandarBrilho(parseInt(slBrilho.value, 10) - 10); });
el("brilho-mais").addEventListener("click", function(){ vibrar(8); mandarBrilho(parseInt(slBrilho.value, 10) + 10); });
el("btn-energia").addEventListener("click", function(){
  folhaEn.classList.add("aberta");
  msgBrilho.textContent = "";
  vBrilho.textContent = "…";
  enviar({t:"brilho_ler"});
});
el("fechar-energia").addEventListener("click", function(){ folhaEn.classList.remove("aberta"); });
folhaEn.addEventListener("click", function(e){ if (e.target === folhaEn) folhaEn.classList.remove("aberta"); });

// suspender (com confirmação no "Agora" e timer nos outros)
let fimSusp = 0, confirmaAte = 0;
const btnAgora = el("susp-agora");
function mmss(seg){ const m = Math.floor(seg / 60), s2 = seg % 60; return (m >= 60 ? Math.floor(m/60) + "h" + String(m%60).padStart(2,"0") : m) + ":" + String(s2).padStart(2,"0"); }
function mostrarSusp(){
  const resta = fimSusp ? Math.max(0, Math.round((fimSusp - Date.now()) / 1000)) : 0;
  const caixa = el("susp-status");
  if (resta > 0){
    caixa.hidden = false;
    el("susp-texto").textContent = resta <= 3 ? "Suspendendo o PC…" : "O PC vai suspender em " + mmss(resta);
    el("susp-mini").textContent = resta <= 3 ? "· suspendendo" : "· " + mmss(resta);
  } else {
    caixa.hidden = true; el("susp-mini").textContent = ""; fimSusp = 0;
  }
}
window.mtSusp = function(resta){ fimSusp = resta > 0 ? Date.now() + resta * 1000 : 0; mostrarSusp(); };
setInterval(function(){ if (fimSusp) mostrarSusp(); if (confirmaAte && Date.now() > confirmaAte){ confirmaAte = 0; btnAgora.textContent = "Agora"; btnAgora.classList.remove("confirmar"); } }, 1000);
document.querySelectorAll(".susp").forEach(function(btn){
  btn.addEventListener("click", function(){
    const min = parseInt(btn.dataset.min, 10);
    if (min === 0 && !confirmaAte){
      confirmaAte = Date.now() + 3500;
      btnAgora.textContent = "Confirmar"; btnAgora.classList.add("confirmar"); vibrar(20);
      return;
    }
    confirmaAte = 0; btnAgora.textContent = "Agora"; btnAgora.classList.remove("confirmar");
    vibrar(15);
    enviar({t:"suspender", min:min});
  });
});
el("susp-cancelar").addEventListener("click", function(){ vibrar(10); enviar({t:"cancelar_susp"}); });

// ---------- tela do código ----------
const folhaCod = el("codigo"), codInput = el("cod-input"), codMsg = el("cod-msg");
function pedirCodigo(msg){
  status("off", "Aguardando código");
  codMsg.textContent = msg || "Digite o código de 4 números que aparece na tela do PC.";
  codMsg.classList.toggle("erro", !!msg);
  codInput.value = "";
  folhaCod.classList.add("aberta");
  setTimeout(function(){ codInput.focus(); }, 60);
}
function confirmarCodigo(){
  const v = codInput.value.replace(/\D/g, "");
  if (v.length !== 4){ codMsg.textContent = "O código tem 4 números."; codMsg.classList.add("erro"); return; }
  codigo = v; gravar("codigo", v); bloqueado = false;
  folhaCod.classList.remove("aberta"); codInput.blur();
  if (ws){ try{ ws.onclose = null; ws.close(); }catch(e){} }
  conectar();
}
el("cod-ok").addEventListener("click", confirmarCodigo);
codInput.addEventListener("keydown", function(e){ if (e.key === "Enter") confirmarCodigo(); });

// ---------- botão de sincronizar ----------
const btnSinc = el("sincronizar");
btnSinc.addEventListener("click", function(){
  btnSinc.classList.remove("girando"); void btnSinc.offsetWidth; btnSinc.classList.add("girando");
  vibrar(15);
  bloqueado = false;
  reconectar();
});
document.getElementById("status-txt").parentNode.addEventListener("click", function(){ btnSinc.click(); });

// evita zoom por gesto no iOS
document.addEventListener("gesturestart", function(e){ e.preventDefault(); });

conectar();
})();
</script>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# Página do PC (QR code para abrir no celular)
# ---------------------------------------------------------------------------

PAGINA_PC = r"""<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Touch Cell</title>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<style>
:root{--bg:#0f1115;--card:#171a21;--borda:#2b303b;--txt:#e9ebf1;--sub:#8b93a4;--acc:#4f8cff;--ok:#2ecc71}
body{margin:0;min-height:100vh;background:var(--bg);color:var(--txt);font-family:system-ui,"Segoe UI",Roboto,sans-serif;display:flex;align-items:center;justify-content:center;padding:24px;box-sizing:border-box}
.card{background:var(--card);border:1px solid var(--borda);border-radius:22px;padding:36px 40px;max-width:460px;width:100%;text-align:center}
h1{margin:0 0 6px;font-size:26px}
.sub{color:var(--sub);margin:0 0 26px;font-size:15px}
#qr{display:inline-block;background:#fff;padding:14px;border-radius:14px;min-height:20px}
#qr img,#qr canvas{display:block}
.url{margin-top:20px;font-family:ui-monospace,Consolas,monospace;font-size:15px;background:#0f1115;border:1px solid var(--borda);border-radius:10px;padding:10px 12px;word-break:break-all;user-select:all}
.codigo{margin-top:16px;color:var(--sub);font-size:15px}
.codigo b{color:var(--txt);font-size:22px;letter-spacing:6px;margin-left:6px}
.estado{margin-top:22px;display:inline-flex;align-items:center;gap:8px;font-size:15px;color:var(--sub)}
.dot{width:10px;height:10px;border-radius:50%;background:#f5b041}
.estado.ok{color:var(--ok)}.estado.ok .dot{background:var(--ok)}
.passos{text-align:left;color:var(--sub);font-size:14px;line-height:1.7;margin:26px 0 0;padding-left:20px}
.creditos{margin-top:26px;padding-top:18px;border-top:1px solid var(--borda);color:var(--sub);font-size:13.5px}
.creditos b{color:var(--txt)}
</style>
</head>
<body>
<div class="card">
  <h1>Touch Cell</h1>
  <p class="sub">Aponte a câmera do celular para o código</p>
  <div id="qr"></div>
  <div class="url">{{URL}}</div>
  <div class="codigo">Código de acesso:<b>{{CODIGO}}</b></div>
  <div class="estado" id="estado"><span class="dot"></span><span id="estado-txt">Esperando o celular…</span></div>
  <ol class="passos">
    <li>Deixe o celular no <b>mesmo Wi-Fi</b> do PC.</li>
    <li>Leia o QR code ou digite o endereço no navegador do celular.</li>
    <li>Dica: use "Adicionar à tela inicial" para abrir como app.</li>
  </ol>
  <div class="creditos">Criado por <b>Isaque Andrade</b> e <b>Joel Andrade</b></div>
</div>
<script>
try { new QRCode(document.getElementById("qr"), {text:"{{URL}}", width:220, height:220, correctLevel:QRCode.CorrectLevel.M}); }
catch(e){ document.getElementById("qr").style.display = "none"; }
async function verificar(){
  try{
    const r = await fetch("/status", {cache:"no-store"}); const d = await r.json();
    const ok = d.conectados > 0;
    document.getElementById("estado").classList.toggle("ok", ok);
    document.getElementById("estado-txt").textContent = ok ? "Celular conectado!" : "Esperando o celular…";
  }catch(e){ document.getElementById("estado-txt").textContent = "Programa encerrado"; }
}
verificar(); setInterval(verificar, 2000);
</script>
</body>
</html>
"""


if __name__ == "__main__":
    main()
