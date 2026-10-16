#!/usr/bin/env python3
"""Native macOS Tk interface. All network work runs off the UI thread."""
import json
import io
import queue
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from engine import Lab, STARTERS, DATA, fetch_preview, youtube_url, receiver_preview
from PIL import Image, ImageTk, ImageOps


def clock_time(seconds):
    if seconds is None:
        return '—'
    seconds = max(0, int(seconds))
    hours, rest = divmod(seconds, 3600)
    minutes, seconds = divmod(rest, 60)
    return f'{hours}:{minutes:02d}:{seconds:02d}' if hours else f'{minutes}:{seconds:02d}'


def player_status(status, connecting=False):
    if connecting:
        return '● Connecting…', '#e6a23c'
    if status.get('connected'):
        return '● Connected', '#53b879'
    return '● Disconnected', '#ee7069'


def transport_availability(status, selected=False, busy=False, connecting=False):
    if busy or connecting or not status.get('connected'):
        return False, False
    state = status.get('state')
    return (state not in ('PLAYING', 'BUFFERING') and
            (state == 'PAUSED' or bool(selected))), state == 'PLAYING'


class App:
    def __init__(self, root, rejoin_speaker=None):
        self.root = root
        self.root.title('Moozaq · Cast Lab')
        from pathlib import Path
        icon_path = Path(__file__).resolve().parent / 'Moozaq Cast Lab.app/Contents/Resources/Moozaq.png'
        if icon_path.exists():
            self.app_icon = tk.PhotoImage(file=str(icon_path))
            self.root.iconphoto(True, self.app_icon)
        self.root.geometry('1000x760')
        self.root.minsize(800, 680)
        self.connection_pending = threading.Event()
        self.rejoin_speaker = rejoin_speaker
        self.lab = Lab()
        self.jobs = queue.Queue()
        self.previews = queue.Queue()
        self.player_art = queue.Queue()
        self.player_generation = 0
        self.preview_generation = 0
        self.preview_source = None
        self.preview_memory = {}
        self.busy = False
        self.active_jobs = 0
        self.device_ids = {}
        self.actions = []
        self.last_events = ''
        self.last_recorded_status = None
        self.receiver_track = None
        self.closed = False
        self.device = tk.StringVar()
        self.source = tk.StringVar(value='https://www.youtube.com/watch?v=' + STARTERS[0])
        self.message = tk.StringVar(value='Discovering speakers on your local network…')
        self.state = tk.StringVar(value='Not connected')
        self.source_info = tk.StringVar(value='No audio resolved yet. Choose one of your six mixes.')
        self.volume = tk.DoubleVar(value=15)
        self.seek = tk.StringVar(value='60')
        style = ttk.Style()
        style.configure('Title.TLabel', font=('Helvetica', 21, 'bold'))
        style.configure('Section.TLabelframe.Label', font=('Helvetica', 13, 'bold'))
        style.configure('Status.TLabel', font=('Menlo', 12))
        page = ttk.Frame(root, padding=20)
        page.pack(fill='both', expand=True)
        header = ttk.Frame(page)
        header.pack(fill='x')
        if icon_path.exists():
            with Image.open(icon_path) as original:
                self.header_logo = ImageTk.PhotoImage(ImageOps.contain(original, (44, 44), Image.Resampling.LANCZOS))
            ttk.Label(header, image=self.header_logo).pack(side='left', padx=(0, 10))
        ttk.Label(header, text='Moozaq / Cast Lab', style='Title.TLabel').pack(side='left')
        ttk.Label(page, text='Regular YouTube links · no Premium · compare three playback routes', padding=(0, 5)).pack(anchor='w')

        device_box = ttk.LabelFrame(page, text='Speaker', padding=10, style='Section.TLabelframe')
        device_box.pack(fill='x', pady=8)
        self.device_picker = ttk.Combobox(device_box, textvariable=self.device, state='readonly', width=35)
        self.device_picker.pack(side='left', padx=(0, 8))
        self.add_button(device_box, 'Connect / rejoin', self.connect, side='left')
        self.add_button(device_box, 'Disconnect remote', lambda: self.work('Disconnecting remote', self.lab.detach), side='left')


        notebook = ttk.Notebook(page)
        browse_tab = self.scroll_tab(notebook, 'Browse')
        tests_tab = self.scroll_tab(notebook, 'Playback tests')
        diagnostics_tab = ttk.Frame(notebook, padding=10)
        notebook.add(diagnostics_tab, text='Diagnostics')

        source_box = ttk.LabelFrame(browse_tab, text='Browse mixes', padding=10, style='Section.TLabelframe')
        source_box.pack(fill='x', pady=5)
        self.mix_labels = []
        for index, video in enumerate(STARTERS, 1):
            try:
                title = json.loads((DATA / 'metadata' / (video + '.json')).read_text())['title']
            except (OSError, ValueError, KeyError):
                title = 'Mix ' + str(index) + ' · ' + video
            self.mix_labels.append(str(index) + '. ' + title)
        self.mix_choice = tk.StringVar()
        self.source_picker = ttk.Combobox(source_box, textvariable=self.mix_choice, state='readonly', values=self.mix_labels, width=75)
        # Hidden combobox retains the selection model; the browser exposes all six choices.
        cards = ttk.Frame(source_box); cards.pack(fill='x')
        self.catalog_images = []
        self.catalog_buttons = []
        for index, title in enumerate(self.mix_labels):
            card = ttk.Frame(cards, padding=6)
            card.grid(row=index // 2, column=index % 2, sticky='nsew', padx=3, pady=3)
            cards.columnconfigure(index % 2, weight=1, uniform='mix')
            thumbnail = None
            try:
                import base64
                record = json.loads((DATA / 'metadata' / (STARTERS[index] + '.json')).read_text())
                with Image.open(io.BytesIO(base64.b64decode(record['image']))) as original:
                    thumbnail = ImageTk.PhotoImage(ImageOps.contain(original, (100, 60)))
                self.catalog_images.append(thumbnail)
            except (OSError, ValueError, KeyError):
                pass
            card.columnconfigure(1, weight=1)
            artwork = ttk.Label(card, image=thumbnail or '', text='' if thumbnail else 'No artwork')
            artwork.grid(row=0, column=0, rowspan=2, sticky='nw', padx=(0, 10))
            caption = ttk.Label(card, text=title, wraplength=190, anchor='w', justify='left')
            caption.grid(row=0, column=1, sticky='ew')
            button = ttk.Button(card, text='Play', command=lambda i=index: self.play_catalog_mix(i))
            button.grid(row=1, column=1, sticky='w', pady=(8, 0))
            button.state(['disabled'])
            self.actions.append(button)
            self.catalog_buttons.append(button)
        self.source_picker.bind('<<ComboboxSelected>>', self.choose_mix)
        self.actions.append(self.source_picker)
        self.source.trace_add('write', self.sync_mix_choice)
        self.sync_mix_choice()
        preview = ttk.LabelFrame(diagnostics_tab, text='Selected source details', padding=8); preview.pack(fill='x', pady=(8, 0))
        self.poster = ttk.Label(preview, text='Loading artwork…', width=30, anchor='center')
        self.poster.pack(side='left', padx=(0, 12))
        preview_text = ttk.Frame(preview); preview_text.pack(side='left', fill='x', expand=True)
        self.preview_title = tk.StringVar(value='Loading title…')
        self.preview_channel = tk.StringVar()
        ttk.Label(preview_text, textvariable=self.preview_title, font=('Helvetica', 14, 'bold'), wraplength=450).pack(anchor='w')
        ttk.Label(preview_text, textvariable=self.preview_channel, wraplength=450).pack(anchor='w', pady=(8, 0))
        self.switch_button = self.add_button(preview_text, 'Play selected mix', self.do_play_selected)
        self.switch_button.state(['disabled'])
        ttk.Button(diagnostics_tab, text='Refresh artwork / title', command=lambda: self.load_preview(force=True)).pack(anchor='w', pady=5)
        row = ttk.Frame(tests_tab); row.pack(fill='x', pady=7)
        self.add_button(row, 'Resolve audio only', self.resolve, side='left')
        ttk.Label(diagnostics_tab, textvariable=self.source_info, wraplength=730).pack(anchor='w')

        tests = ttk.LabelFrame(tests_tab, text='Compare playback routes', padding=10, style='Section.TLabelframe')
        tests.pack(fill='x', pady=5)
        for col, (mode, label, description) in enumerate([
            ('direct', 'A · Direct to Nest', 'Nest fetches the resolved YouTube audio URL.\nMac need not serve the audio.'),
            ('relay', 'B · Relay through Mac', 'Mac forwards audio and request headers.\nKeep this app and Mac running.'),
            ('cache', 'C · Download, then cast', 'Wait for the complete audio-only file.\nMac serves it with seeking support.')]):
            box = ttk.Frame(tests, padding=5); box.grid(row=col, column=0, sticky='ew'); tests.columnconfigure(0, weight=1)
            self.add_button(box, label, lambda m=mode: self.test(m))
            ttk.Label(box, text=description, wraplength=700).pack(anchor='w', pady=5)
        ttk.Label(tests, text='Tests use the volume selected below (default 15%). Extraction is unofficial; success here does not establish production permission.', wraplength=700).grid(row=3, column=0, sticky='w')
        self.add_button(tests_tab, 'Play original test tone', lambda: self.test('tone'))

        player_box = ttk.LabelFrame(page, text='Now playing', padding=10, style='Section.TLabelframe')
        player_box.pack(side='bottom', fill='x', pady=5)
        status_row = ttk.Frame(player_box); status_row.pack(fill='x')
        self.connection_label = ttk.Label(status_row, text='● Disconnected', foreground='#ee7069')
        self.connection_label.pack(side='left')
        self.playback_label = ttk.Label(status_row, text='No receiver connected')
        self.playback_label.pack(side='right')
        playing_row = ttk.Frame(player_box); playing_row.pack(fill='x', pady=6)
        self.playing_artwork = ttk.Label(playing_row, text='No artwork', width=14)
        self.playing_artwork.pack(side='left', padx=(0, 10))
        self.now_playing_label = ttk.Label(playing_row, text='No active playback', wraplength=600)
        self.now_playing_label.pack(side='left', fill='x', expand=True)
        timeline = ttk.Frame(player_box); timeline.pack(fill='x', pady=(12, 5))
        self.elapsed = ttk.Label(timeline, text='0:00', width=8)
        self.elapsed.pack(side='left')
        self.progress = ttk.Progressbar(timeline, maximum=100, mode='determinate')
        self.progress.pack(side='left', fill='x', expand=True, padx=8)
        self.total = ttk.Label(timeline, text='—', width=8, anchor='e')
        self.total.pack(side='right')
        row = ttk.Frame(player_box); row.pack(fill='x', pady=6)
        ttk.Button(row, text='STOP', command=self.do_stop).pack(side='left', padx=3)
        self.play_button = self.add_button(row, 'Play', self.do_play, side='left')
        self.play_button.state(['disabled'])
        self.pause_button = self.add_button(row, 'Pause', lambda: self.work('Pausing', lambda: self.lab.command('pause')), side='left')
        self.pause_button.state(['disabled'])
        ttk.Spinbox(row, from_=0, to=100, textvariable=self.volume, width=5).pack(side='left', padx=(20, 2))
        self.add_button(row, 'Set volume %', self.do_volume, side='left')
        self.live_volume = ttk.Label(row, text='Receiver: —')
        self.live_volume.pack(side='right')
        row = ttk.Frame(tests_tab); row.pack(fill='x', pady=8)
        ttk.Entry(row, textvariable=self.seek, width=7).pack(side='left', padx=(0, 2))
        self.add_button(row, 'Seek to seconds', self.do_seek, side='left')
        self.add_button(diagnostics_tab, 'Refresh receiver', lambda: self.work('Refreshing receiver', lambda: self.lab.command('refresh')))
        ttk.Label(diagnostics_tab, textvariable=self.state, style='Status.TLabel', wraplength=730).pack(anchor='w', pady=8)

        result = ttk.Frame(page); result.pack(side='bottom', before=player_box, fill='x', pady=7)
        ttk.Label(result, textvariable=self.message, wraplength=650).pack(side='left', fill='x', expand=True)
        ttk.Button(result, text='Cancel job', command=self.lab.cancel).pack(side='right')
        notebook.pack(fill='both', expand=True, pady=6)
        notes_row = ttk.Frame(diagnostics_tab); notes_row.pack(fill='x')
        ttk.Label(notes_row, text='Listening / reconnection notes:').pack(side='left')
        self.notes = ttk.Entry(notes_row); self.notes.pack(side='left', fill='x', expand=True, padx=8)
        ttk.Button(notes_row, text='Export report…', command=self.export).pack(side='right')
        self.log = tk.Text(diagnostics_tab, height=9, font=('Menlo', 10), wrap='word', state='disabled')
        log_scroll = ttk.Scrollbar(diagnostics_tab, orient='vertical', command=self.log.yview)
        log_scroll.pack(side='right', fill='y')
        self.log.configure(yscrollcommand=log_scroll.set)
        self.log.pack(fill='both', expand=True, pady=(8, 0))
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        self.root.after(500, self.tick)
        self.root.after(100, self.load_preview)
        self.root.after(50, self.poll_previews)

    def scroll_tab(self, notebook, title):
        tab = ttk.Frame(notebook)
        notebook.add(tab, text=title)
        canvas = tk.Canvas(tab, highlightthickness=0, borderwidth=0,
                           background=ttk.Style().lookup('TFrame', 'background') or '#333333')
        scrollbar = ttk.Scrollbar(tab, orient='vertical', command=canvas.yview)
        scrollbar.pack(side='right', fill='y')
        canvas.pack(side='left', fill='both', expand=True)
        canvas.configure(yscrollcommand=scrollbar.set)
        content = ttk.Frame(canvas, padding=8)
        window = canvas.create_window(0, 0, window=content, anchor='nw')
        content.bind('<Configure>', lambda event: canvas.configure(scrollregion=canvas.bbox('all')))
        canvas.bind('<Configure>', lambda event: canvas.itemconfigure(window, width=event.width))
        def wheel(event):
            if canvas.bbox('all')[3] > canvas.winfo_height():
                canvas.yview_scroll(-int(event.delta), 'units')
        # Bind only descendants of this tab, leaving other tabs and controls alone.
        def bind_wheel():
            def visit(widget):
                widget.bind('<MouseWheel>', wheel, add='+')
                for child in widget.winfo_children():
                    visit(child)
            visit(content)
        self.root.after_idle(bind_wheel)
        canvas.bind('<MouseWheel>', wheel)
        return content

    def sync_mix_choice(self, *args):
        sources = [youtube_url(video) for video in STARTERS]
        value = self.source.get()
        self.mix_choice.set(self.mix_labels[sources.index(value)] if value in sources else '')

    def play_catalog_mix(self, index):
        if self.busy:
            return
        self.source.set(youtube_url(STARTERS[index]))
        self.do_play_selected()

    def browse_mix(self, index):
        if self.busy:
            return
        self.source_picker.current(index)
        self.choose_mix()

    def choose_mix(self, event=None):
        if self.busy:
            self.sync_mix_choice()
            return
        index = self.source_picker.current()
        if index < 0:
            return
        value = youtube_url(STARTERS[index])
        self.source.set(value)
        self.load_preview()
        self.message.set('Preview selected. Current playback is unchanged; press Play selected mix to switch.')

    def load_preview(self, event=None, force=False):
        value = self.source.get()
        if not value:
            return
        try:
            source = youtube_url(value)
        except ValueError as exc:
            self.preview_generation += 1
            self.preview_source = None
            self.preview_title.set(str(exc))
            self.preview_channel.set('')
            self.poster.configure(image='', text='No artwork')
            return
        if not force and source == self.preview_source:
            return
        self.preview_source = source
        self.preview_generation += 1
        generation = self.preview_generation
        if source in self.preview_memory:
            self.show_preview(*self.preview_memory[source])
            if not force:
                return
            self.preview_channel.set(self.preview_channel.get() + ' · Refreshing…')
        else:
            self.preview_title.set('Loading saved preview…')
            self.preview_channel.set('Only uncached previews need a network request')
            self.poster.configure(image='', text='Loading artwork…')
        def fetch():
            try:
                self.previews.put((generation, fetch_preview(source, force=force), None))
            except Exception as exc:
                self.previews.put((generation, None, str(exc)))
        threading.Thread(target=fetch, daemon=True).start()

    def add_button(self, parent, label, command, side=None):
        button = ttk.Button(parent, text=label, command=command)
        button.pack(side=side or 'top', padx=3, anchor='w')
        self.actions.append(button)
        return button

    def work(self, label, function, urgent=False):
        if self.busy and not urgent:
            return
        self.busy = True
        self.active_jobs += 1
        self.message.set(label + '…')
        for button in self.actions:
            button.state(['disabled'])
        # Capture source and speaker in event handlers before starting the thread.
        def run():
            try:
                function()
                self.jobs.put((True, 'Stopped: receiver confirmed playback ended.' if urgent else label + ' completed.'))
            except Exception as exc:
                self.lab.log('failed', str(exc))
                self.jobs.put((False, str(exc)))
        threading.Thread(target=run, daemon=True).start()

    def resolve(self):
        value = self.source.get()
        if not value:
            self.message.set('Select a saved mix first.'); return
        self.work('Resolving YouTube audio', lambda: self.lab.resolve(value))

    def connect(self):
        target = self.device_ids.get(self.device.get())
        if not target:
            self.message.set('Choose a discovered speaker first.'); return
        if self.busy:
            return
        self.connection_pending.set()
        self.connection_label.configure(text='● Connecting…', foreground='#e6a23c')
        def rejoin():
            try:
                self.lab.connect(target)
            finally:
                self.connection_pending.clear()
        self.work('Rejoining receiver', rejoin)

    def test(self, mode):
        value = self.source.get()
        if mode != 'tone' and not value:
            self.message.set('Select a saved mix first.'); return
        try:
            volume = float(self.volume.get()) / 100
            if not 0 <= volume <= 1:
                raise ValueError()
        except (ValueError, tk.TclError):
            self.message.set('Volume must be between 0 and 100.'); return
        self.work('Running ' + mode + ' test', lambda: self.lab.play_test(mode, value, volume))

    def do_play_selected(self):
        value = self.source.get()
        if not value:
            return
        self.work('Starting selected mix', lambda: self.lab.play_selection(value))

    def do_play(self):
        self.work('Resuming current mix', lambda: self.lab.play_selection(''))

    def do_stop(self):
        if not self.lab.cast and not self.lab.selected_id:
            self.lab.selected_id = self.device_ids.get(self.device.get())
        self.work('Stopping', lambda: self.lab.command('stop'), urgent=True)

    def do_seek(self):
        try:
            value = float(self.seek.get())
            if not 0 <= value <= 864000:
                raise ValueError()
        except ValueError:
            self.message.set('Enter a valid non-negative seek time in seconds.'); return
        self.work('Seeking', lambda: self.lab.command('seek', value))

    def do_volume(self):
        try:
            value = float(self.volume.get()) / 100
            if not 0 <= value <= 1:
                raise ValueError()
        except (ValueError, tk.TclError):
            self.message.set('Volume must be between 0 and 100.'); return
        self.work('Setting volume', lambda: self.lab.command('volume', value))

    def show_preview(self, preview, image):
        self.poster_image = image
        self.poster.configure(image=image or '', text='' if image else 'No artwork', width=0)
        self.preview_title.set(preview['title'])
        notice = preview.get('cache_notice', '')
        self.preview_channel.set(preview['channel'] + (' · ' + notice if notice else ''))

    def poll_previews(self):
        if self.closed:
            return
        while not self.previews.empty():
            generation, preview, error = self.previews.get_nowait()
            if generation != self.preview_generation:
                continue
            if error:
                self.preview_title.set('Preview unavailable')
                self.preview_channel.set(error[:180])
                self.poster.configure(image='', text='No artwork')
            else:
                image = None
                try:
                    with Image.open(io.BytesIO(preview['image'])) as original:
                        thumbnail = ImageOps.contain(original.convert('RGB'), (240, 135), Image.Resampling.LANCZOS)
                    image = ImageTk.PhotoImage(thumbnail)
                except Exception:
                    preview = {**preview, 'cache_notice': 'Artwork unavailable'}
                if preview['source']:
                    self.preview_memory[preview['source']] = (preview, image)
                self.show_preview(preview, image)
        while not self.player_art.empty():
            generation, preview = self.player_art.get_nowait()
            if generation != self.player_generation:
                continue
            try:
                with Image.open(io.BytesIO(preview['image'])) as original:
                    self.playing_image = ImageTk.PhotoImage(ImageOps.contain(original.convert('RGB'), (96, 54)))
                self.playing_artwork.configure(image=self.playing_image, text='', width=0)
            except Exception:
                self.playing_artwork.configure(image='', text='No artwork')
        self.root.after(50, self.poll_previews)

    def sync_receiver_track(self, status):
        active = status.get('connected') and status.get('state') in ('PLAYING', 'PAUSED', 'BUFFERING')
        key = (status.get('device'), status.get('app'), status.get('media_session'),
               status.get('content_key'), status.get('title'), status.get('artwork')) if active else None
        if key == self.receiver_track:
            return
        self.receiver_track = key
        self.player_generation += 1
        self.playing_artwork.configure(image='', text='Loading artwork…' if active else 'No playback')
        if active:
            self.load_receiver_artwork(status)

    def load_receiver_artwork(self, status):
        generation = self.player_generation
        def fetch():
            try:
                preview = fetch_preview(status['source']) if status.get('source') else receiver_preview(status)
                self.player_art.put((generation, preview))
            except Exception:
                self.player_art.put((generation, {'image': b''}))
        threading.Thread(target=fetch, daemon=True).start()

    def update_player(self, status):
        connecting = self.connection_pending.is_set() or status.get('reconnecting', False)
        label, color = player_status(status, connecting)
        self.connection_label.configure(text=label, foreground=color)
        play_enabled, pause_enabled = transport_availability(
            status, False, self.busy, connecting)
        self.play_button.state(['!disabled'] if play_enabled else ['disabled'])
        self.pause_button.state(['!disabled'] if pause_enabled else ['disabled'])
        self.play_button.configure(text='Playing' if status.get('connected') and status.get('state') == 'PLAYING' else 'Play')
        connected = status.get('connected') and not connecting
        current = status.get('state', 'IDLE').title() if connected else 'Connecting…' if connecting else 'Not connected'
        if connected and status.get('state') in ('UNKNOWN', 'IDLE') and status.get('media_session') is None:
            current = 'Ready to play'
        if connected and status.get('idle_reason') == 'ERROR':
            current = 'Playback failed · select a mix to retry'
        device = status.get('device', '') if connected else ''
        # A browsed selection is a preview, not a claim about the current receiver track.
        source = status.get('source')
        previewing = bool(self.source.get() and (not connected or self.source.get() != source))
        can_switch = bool(self.source.get() and connected and not self.busy and (previewing or status.get('state') not in ('PLAYING', 'BUFFERING')))
        self.switch_button.state(['!disabled'] if can_switch else ['disabled'])
        for index, button in enumerate(self.catalog_buttons):
            same = status.get('source') == youtube_url(STARTERS[index])
            playing = same and status.get('state') in ('PLAYING', 'BUFFERING')
            enabled = connected and not self.busy and not playing
            button.state(['!disabled'] if enabled else ['disabled'])
            button.configure(text='Playing' if playing else 'Resume' if same and status.get('state') == 'PAUSED' else 'Play')
        title = status.get('title') or 'Title unavailable'
        self.now_playing_label.configure(text=('Now playing: ' + title if status.get('state') == 'PLAYING' else 'On speaker: ' + title) if connected and status.get('state') in ('PLAYING', 'PAUSED', 'BUFFERING') else 'No active playback')
        if device:
            current += ' · ' + device
        self.playback_label.configure(text=current)
        position = max(0, status.get('position') or 0) if connected else 0
        duration = status.get('duration') if connected else None
        self.progress['value'] = min(100, position / duration * 100) if duration and duration > 0 else 0
        self.elapsed.configure(text=clock_time(position) if connected else '—')
        self.total.configure(text=clock_time(duration))
        self.live_volume.configure(text=f'Receiver: {status.get("volume", "—")}% ' if connected else 'Receiver: —')

    def tick(self):
        if self.closed:
            return
        while not self.jobs.empty():
            okay, result = self.jobs.get_nowait()
            self.active_jobs -= 1
            self.busy = self.active_jobs > 0
            self.message.set(result if okay else 'Failed: ' + result[-600:])
            if not self.busy:
                for button in self.actions:
                    button.state(['!disabled'])
        try:
            devices = self.lab.devices()
            self.device_ids = {name + ' · ' + (model or 'Cast'): uid for uid, name, model in devices}
            self.device_picker['values'] = list(self.device_ids)
            if self.rejoin_speaker:
                target = next((key for key in self.device_ids
                               if key.split(' · ')[0].casefold() == self.rejoin_speaker.casefold()), None)
                if target and not self.busy:
                    self.device.set(target)
                    self.rejoin_speaker = None
                    self.connect()
            elif not self.device.get():
                bedroom = next((key for key in self.device_ids if key.startswith('Bedroom speaker ·')), None)
                if bedroom:
                    self.device.set(bedroom)
                    if not self.busy:
                        self.message.set('Bedroom speaker found. Connect, then choose a test.')
            status = self.lab.status()
            self.sync_receiver_track(status)
            self.update_player(status)
            if status['connected']:
                self.state.set(f'{status["device"]}  |  {status["state"]}  |  {status["position"]} / {status["duration"] or "?"} seconds  |  Volume {status["volume"]}%\n{status["title"] or "No media"}  ·  Route: {status["mode"]}  ·  Receiver: {status["app"]}  ·  Idle: {status["idle_reason"] or "—"}')
                record_key = (status['state'], int(status['position']) // 10, status['volume'], status['title'])
                if record_key != self.last_recorded_status:
                    self.lab.log('receiver', json.dumps(status))
                    self.last_recorded_status = record_key
            else:
                self.state.set('Remote disconnected. Rejoin to read current playback; no reload is sent.\nFor relay / cached audio, leave this app running so the media server stays available.')
            if self.lab.resolved and self.lab.resolved.get('source_url') == self.preview_source:
                info = self.lab.resolved
                self.source_info.set(f'{info["title"]}\n{info.get("duration", "?")} seconds · {info.get("ext")} · {info.get("acodec")} · format {info.get("format_id")}')
            elif not self.source.get():
                self.source_info.set('Current receiver playback · no saved YouTube mix selected.')
            else:
                self.source_info.set('Resolve audio to inspect its format. Artwork/title fetching does not start playback.')
            events = '\n'.join(e['time'] + '  ' + e['event'] + '  ' + e['detail'] for e in self.lab.events)
            if events != self.last_events:
                self.log.configure(state='normal'); self.log.delete('1.0', 'end'); self.log.insert('end', events); self.log.see('end'); self.log.configure(state='disabled')
                self.last_events = events
        except Exception as exc:
            self.message.set('Status update: ' + str(exc))
        self.root.after(1000, self.tick)

    def export(self):
        path = filedialog.asksaveasfilename(defaultextension='.json', initialfile='moozaq-test-report.json', initialdir=str(DATA))
        if path:
            self.lab.export(path, self.notes.get())
            self.message.set('Report saved to ' + path)

    def close(self):
        if self.busy and not messagebox.askyesno('Quit test?', 'A test is running. Cancel it and close the app?'):
            return
        if self.lab.mode in ('relay', 'cache', 'tone') and not messagebox.askyesno('Stop serving audio?', 'Closing the app stops the Mac media server. Receiver playback may stop. Close anyway?'):
            return
        self.closed = True
        self.root.destroy()
        self.lab.close()


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description='Moozaq Cast Lab')
    parser.add_argument('--rejoin-speaker', help='Discover and join this speaker without loading media or changing volume')
    args = parser.parse_args()
    root = tk.Tk()
    app = App(root, rejoin_speaker=args.rejoin_speaker)
    root.mainloop()
