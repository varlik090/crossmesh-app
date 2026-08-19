import socket
import threading
import io
import time
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.tabbedpanel import TabbedPanel, TabbedPanelHeader
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.image import Image
from kivy.uix.switch import Switch
from kivy.core.image import Image as CoreImage
from kivy.clock import Clock

try:
    from plyer import gyro, clipboard, camera
except ImportError:
    gyro, clipboard, camera = None, None, None

class CrossMeshFullClient(TabbedPanel):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.do_default_tab = False
        self.connected = False
        self.tcp_sock = None
        self.udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        
        # --- TAB 1: BAĞLANTI VE EKRAN ---
        self.tab_display = TabbedPanelHeader(text="Ekran & Dokunmatik")
        display_layout = BoxLayout(orientation='vertical')
        
        # Üst Bağlantı Barı
        conn_bar = GridLayout(cols=5, size_hint_y=0.12, padding=5, spacing=5)
        conn_bar.add_widget(Label(text="PC IP:", size_hint_x=0.15))
        self.ip_input = TextInput(text="192.168.1.100", multiline=False, size_hint_x=0.3)
        conn_bar.add_widget(self.ip_input)
        
        conn_bar.add_widget(Label(text="PIN:", size_hint_x=0.1))
        self.pin_input = TextInput(text="", password=True, multiline=False, size_hint_x=0.25)
        conn_bar.add_widget(self.pin_input)
        
        self.btn_connect = Button(text="Bağlan", background_color=(0.2, 0.8, 0.2, 1), size_hint_x=0.2)
        self.btn_connect.bind(on_press=self.toggle_connection)
        conn_bar.add_widget(self.btn_connect)
        display_layout.add_widget(conn_bar)

        # Görüntü Alanı
        self.screen_view = Image(size_hint_y=0.88)
        self.screen_view.bind(on_touch_down=self.send_touch_event)
        display_layout.add_widget(self.screen_view)
        
        self.tab_display.content = display_layout
        self.add_widget(self.tab_display)

        # --- TAB 2: DONANIM KÖPRÜSÜ (Kamera, Mic, Jiroskop) ---
        self.tab_hardware = TabbedPanelHeader(text="Donanım Paylaşımı")
        hw_layout = GridLayout(cols=2, padding=20, spacing=20)
        
        hw_layout.add_widget(Label(text="Kamera Paylaşımı (Virtual Cam):"))
        self.sw_cam = Switch()
        hw_layout.add_widget(self.sw_cam)

        hw_layout.add_widget(Label(text="Mikrofon Ses Yayını:"))
        self.sw_mic = Switch()
        hw_layout.add_widget(self.sw_mic)

        hw_layout.add_widget(Label(text="Jiroskop / Direksiyon Modu:"))
        self.sw_gyro = Switch()
        hw_layout.add_widget(self.sw_gyro)

        self.tab_hardware.content = hw_layout
        self.add_widget(self.tab_hardware)

        # --- TAB 3: STREAM DECK / MAKROLAR ---
        self.tab_macros = TabbedPanelHeader(text="Makro Paneli")
        macro_layout = GridLayout(cols=3, padding=10, spacing=10)
        
        macros = ["CAD Undo", "Render Start", "Zoom Fit", "View Top", "View Side", "View Front", "Esc / Abort", "Save All", "Precision Mode"]
        for m in macros:
            btn = Button(text=m, background_color=(0.3, 0.4, 0.6, 1))
            btn.bind(on_press=lambda inst, name=m: self.send_macro(name))
            macro_layout.add_widget(btn)

        self.tab_macros.content = macro_layout
        self.add_widget(self.tab_macros)

        # --- TAB 4: AKILLI PANO ---
        self.tab_clip = TabbedPanelHeader(text="Akıllı Pano")
        clip_layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        self.txt_clip = TextInput(text="PC ile paylaşılan metinler burada görünür...", readonly=True)
        clip_layout.add_widget(self.txt_clip)
        
        btn_sync = Button(text="Pano Metnini PC'ye Gönder", size_hint_y=0.2)
        btn_sync.bind(on_press=self.send_clipboard_to_pc)
        clip_layout.add_widget(btn_sync)

        self.tab_clip.content = clip_layout
        self.add_widget(self.tab_clip)

    # --- AĞ VE İŞLETİM MİMARİSİ ---
    def toggle_connection(self, instance):
        if not self.connected:
            threading.Thread(target=self._connect_thread, daemon=True).start()

    def _connect_thread(self):
        ip = self.ip_input.text.strip()
        pin = self.pin_input.text.strip()
        try:
            self.tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.tcp_sock.connect((ip, 9999))
            
            if self.tcp_sock.recv(1024).decode('utf-8') == "AUTH_REQ":
                self.tcp_sock.sendall(pin.encode('utf-8'))
                if self.tcp_sock.recv(1024).decode('utf-8') == "AUTH_OK":
                    self.connected = True
                    self.btn_connect.text = "Bağlandı"
                    self.btn_connect.background_color = (0, 0.6, 1, 1)
                    
                    threading.Thread(target=self._receive_screen_loop, daemon=True).start()
                    threading.Thread(target=self._gyro_loop, daemon=True).start()
        except Exception:
            self.btn_connect.text = "Hata!"

    def _receive_screen_loop(self):
        while self.connected:
            try:
                size_bytes = self.tcp_sock.recv(4)
                if not size_bytes: break
                size = int.from_bytes(size_bytes, byteorder='big')
                
                data = bytearray()
                while len(data) < size:
                    packet = self.tcp_sock.recv(size - len(data))
                    if not packet: break
                    data.extend(packet)

                data_bytes = bytes(data)
                Clock.schedule_once(lambda dt: self._update_texture(data_bytes))
            except Exception:
                break

    def _update_texture(self, data):
        try:
            buf = io.BytesIO(data)
            cim = CoreImage(buf, ext="jpg")
            self.screen_view.texture = cim.texture
        except Exception:
            pass

    def send_touch_event(self, instance, touch):
        if self.connected and self.screen_view.collide_point(*touch.pos):
            norm_x = int((touch.x / self.screen_view.width) * 1920)
            norm_y = int((1 - (touch.y / self.screen_view.height)) * 1080)
            cmd = f"TOUCH:{norm_x}:{norm_y}"
            try: self.tcp_sock.sendall(cmd.encode('utf-8'))
            except Exception: pass

    def send_macro(self, macro_name):
        if self.connected:
            cmd = f"MACRO:{macro_name}"
            try: self.tcp_sock.sendall(cmd.encode('utf-8'))
            except Exception: pass

    def send_clipboard_to_pc(self, instance):
        if self.connected and clipboard:
            text = clipboard.paste()
            if text:
                cmd = f"CLIPBOARD:{text}"
                try: self.tcp_sock.sendall(cmd.encode('utf-8'))
                except Exception: pass

    def _gyro_loop(self):
        target_ip = self.ip_input.text.strip()
        while self.connected:
            if self.sw_gyro.active and gyro:
                try:
                    val = gyro.rotation
                    if val:
                        msg = f"GYRO:{val[0]}:{val[1]}"
                        self.udp_sock.sendto(msg.encode('utf-8'), (target_ip, 9998))
                except Exception: pass
            time.sleep(0.05)

class CrossMeshApp(App):
    def build(self):
        return CrossMeshFullClient()

if __name__ == '__main__':
    CrossMeshApp().run()