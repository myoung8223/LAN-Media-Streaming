using System.Drawing;
using System.Windows.Forms;

namespace LanMediaSender;

internal class MainForm : Form
{
    private readonly TextBox _name = new() { Width = 200 };
    private readonly TextBox _ip = new() { Width = 200 };
    private readonly TextBox _port = new() { Width = 80 };
    private readonly TextBox _password = new() { Width = 200, UseSystemPasswordChar = true };
    private readonly ComboBox _source = new() { Width = 220, DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly CheckBox _encrypt = new() { Text = "Encrypt connection", AutoSize = true };
    private readonly CheckBox _compress = new() { Text = "Compress audio", AutoSize = true };
    private readonly CheckBox _video = new() { Text = "Stream video", AutoSize = true };
    // Free-form video knobs (replacing the old quality dropdown). Small centered
    // boxes; parsed, clamped, and reflected back at stream start.
    private readonly TextBox _vWidth   = new() { Width = 50, TextAlign = HorizontalAlignment.Center };
    private readonly TextBox _vHeight  = new() { Width = 50, TextAlign = HorizontalAlignment.Center };
    private readonly TextBox _vBitrate = new() { Width = 58, TextAlign = HorizontalAlignment.Center };
    private readonly TextBox _vFps     = new() { Width = 50, TextAlign = HorizontalAlignment.Center };
    private readonly Label _vX = new()
        { Text = "×", AutoSize = false, Width = 16, Height = 22, TextAlign = ContentAlignment.MiddleCenter };
    private readonly ToolTip _tips = new() { AutoPopDelay = 15000, InitialDelay = 400, ReshowDelay = 100 };
    private readonly CheckBox _cursor = new() { Text = "Show mouse cursor", AutoSize = true };
    private readonly CheckBox _incAudio = new() { Text = "Include audio", AutoSize = true };

    private readonly ComboBox _bitrate = new() { Width = 220, DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly Button _startStop = new() { Text = "Start streaming", Width = 220, Height = 38 };
    private readonly LevelBar _level = new() { Width = 320, Height = 14 };
    private readonly Label _status = new() { Text = "Stopped", AutoSize = true };
    private readonly Label _pin = new() { AutoSize = false, Width = 340, Height = 58 };
    private readonly Button _clearPin = new() { Text = "Clear pinned certificate", Width = 220, Height = 28 };

    private IStreamer? _streamer;
    private readonly Settings _settings = Settings.Load();

    // Palette mirrored from the Android app's colors.xml.
    private static readonly Color Bg = Color.FromArgb(0x0E, 0x11, 0x16);
    private static readonly Color PanelC = Color.FromArgb(0x17, 0x1C, 0x24);
    private static readonly Color TextC = Color.FromArgb(0xE6, 0xED, 0xF5);
    private static readonly Color MutedC = Color.FromArgb(0x93, 0xA1, 0xB5);
    private static readonly Color AccentC = Color.FromArgb(0x2E, 0xCC, 0x9B);
    private static readonly Color DangerC = Color.FromArgb(0xFF, 0x5C, 0x6C);
    // Muted gray for "unavailable" controls — visible on the dark bg, unlike
    // WinForms' own disabled text which renders nearly black here.
    private static readonly Color DisabledC = Color.FromArgb(0x5B, 0x66, 0x78);

    // App identity (shown in the title bar and the About box).
    private const string AppTitle = "LAN Media Sender";
    private const string AppVersion = "1.1.0";
    // Placeholder — update once the public repo exists.
    private const string GitHubUrl = "https://github.com/myoung8223/LAN-Media-Streaming";

    public MainForm()
    {
        Text = AppTitle;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        ClientSize = new Size(672, 398);
        Font = new Font("Segoe UI", 9f);
        BackColor = Bg;
        ForeColor = TextC;
        TryLoadIcon();

        const int leftX = 22;
        const int rightX = 350;
        const int colW = 300;
        const int checkGap = 20;

        // Caption label spanning a column, centered.
        Label CL(string t, int colX, int yy) => new()
        {
            Text = t, Left = colX, Top = yy, Width = colW, Height = 16, AutoSize = false,
            TextAlign = ContentAlignment.MiddleCenter, ForeColor = MutedC,
        };
        // Center a fixed-width control within a column.
        void Place(Control c, int colX, int yy) { c.Top = yy; c.Left = colX + (colW - c.Width) / 2; Controls.Add(c); }
        // Center a pair of checkboxes as one group within a column.
        void PlacePair(CheckBox a, CheckBox b, int colX, int yy)
        {
            int total = a.PreferredSize.Width + checkGap + b.PreferredSize.Width;
            int startX = colX + (colW - total) / 2;
            a.Top = yy; a.Left = startX; Controls.Add(a);
            b.Top = yy; b.Left = startX + a.PreferredSize.Width + checkGap; Controls.Add(b);
        }

        // Center the text typed inside the entry fields.
        _name.TextAlign = HorizontalAlignment.Center;
        _ip.TextAlign = HorizontalAlignment.Center;
        _port.TextAlign = HorizontalAlignment.Center;
        _password.TextAlign = HorizontalAlignment.Center;

        // Field sizes for the two-column layout.
        _name.Width = 250; _ip.Width = 250; _password.Width = 250; _port.Width = 120;
        _source.Width = 250; _bitrate.Width = 250;
        _startStop.Width = 260; _startStop.Height = 44;
        _clearPin.Width = 260; _clearPin.Height = 30;
        _level.Width = 260; _level.Height = 5;

        // Larger type for the four connection fields (13pt vs the 9pt base).
        // Auto-grows their height, which reads better across a room and fills out
        // the left column so it no longer trails off into empty space.
        var fieldFont = new Font("Segoe UI", 13f);
        _name.Font = fieldFont; _ip.Font = fieldFont; _port.Font = fieldFont; _password.Font = fieldFont;

        // ---------------- LEFT COLUMN (connection + audio) ----------------
        Controls.Add(CL("Receiver name (set on receiver app)", leftX, 14)); Place(_name, leftX, 34);
        Controls.Add(CL("Receiver IP address (fallback)", leftX, 72)); Place(_ip, leftX, 92);
        Controls.Add(CL("Port", leftX, 130)); Place(_port, leftX, 150);
        Controls.Add(CL("Password (if set in receiver app)", leftX, 188)); Place(_password, leftX, 208);
        Controls.Add(CL("Audio source", leftX, 246));
        _source.Items.AddRange(new object[] { "System audio", "Microphone" });
        Place(_source, leftX, 264);

        PlacePair(_encrypt, _compress, leftX, 300);

        Controls.Add(CL("Audio quality", leftX, 324));
        _bitrate.Items.AddRange(new object[]
        {
            "Music — 128 kbps (recommended)",
            "High — 192 kbps",
            "Balanced — 96 kbps",
            "Voice — 64 kbps",
        });
        Place(_bitrate, leftX, 342);

        // ---------------- vertical divider ----------------
        Controls.Add(new Panel { Left = 336, Top = 14, Width = 1, Height = 369, BackColor = PanelC });

        // ---------------- RIGHT COLUMN (video + session) ----------------
        _video.Top = 14; Controls.Add(_video);            // "Stream video" mode toggle (aligns with the left column's first label)
        _video.Left = rightX + (colW - _video.PreferredSize.Width) / 2;

        // Three video knobs in one row: Resolution (W×H), Bitrate (Mbps), FPS.
        // Absolute layout, centered as a group within the right column.
        // Sub-caption at an explicit x/width (CL centers over the whole column).
        Label SL(string t, int lx, int yy, int lw) => new()
        {
            Text = t, Left = lx, Top = yy, Width = lw, Height = 16, AutoSize = false,
            TextAlign = ContentAlignment.MiddleCenter, ForeColor = MutedC, UseMnemonic = false,
        };
        const int vLabelY = 44, vBoxY = 63;
        Controls.Add(SL("Resolution (WxH)", 368, vLabelY, 120));
        Controls.Add(SL("Bitrate (Mbps)",   485, vLabelY, 96));
        Controls.Add(SL("FPS",              580, vLabelY, 50));

        _vWidth.Top = vBoxY;   _vWidth.Left = 370;   Controls.Add(_vWidth);
        _vX.Top = vBoxY;       _vX.Left = 420;       Controls.Add(_vX);
        _vHeight.Top = vBoxY;  _vHeight.Left = 436;  Controls.Add(_vHeight);
        _vBitrate.Top = vBoxY; _vBitrate.Left = 504; Controls.Add(_vBitrate);
        _vFps.Top = vBoxY;     _vFps.Left = 580;     Controls.Add(_vFps);

        const string resTip =
            "Maximum output size. The screen is scaled to fit inside Width×Height, "
            + "keeping its aspect ratio, and is never upscaled beyond your display's own "
            + "resolution. Default 1920×1080.";
        _tips.SetToolTip(_vWidth, resTip);
        _tips.SetToolTip(_vHeight, resTip);
        _tips.SetToolTip(_vBitrate,
            "H.264 target bitrate in megabits/sec (1–50). ~10 suits 1080p30; higher "
            + "resolutions and frame rates want more. Above ~15 Mbps, prefer wired Ethernet.");
        _tips.SetToolTip(_vFps,
            "Frames per second (10–60). 30 is the safe default. 60 needs a panel that "
            + "supports 1080p60 (H.264 Level 4.2) and, at higher settings, wired Ethernet.");

        PlacePair(_cursor, _incAudio, rightX, 96);

        Place(_startStop, rightX, 128);
        Place(_level, rightX, 180);

        // Sender status section: caption + a thin rule (same dark tone as the
        // vertical divider), then the two-line metrics readout below it.
        Controls.Add(CL("Sender status", rightX, 195));
        Controls.Add(new Panel { Left = rightX, Top = 213, Width = colW, Height = 1, BackColor = PanelC });

        // Two lines tall so the full streaming metrics wrap instead of being
        // clipped after "cap" — fps · cap/enc ms · Mbit/s all stay visible.
        _status.AutoSize = false; _status.Width = colW; _status.Height = 34;
        _status.TextAlign = ContentAlignment.MiddleCenter;
        Place(_status, rightX, 219);

        Controls.Add(CL("Receiver certificate (verify once with receiver)", rightX, 259));
        _pin.AutoSize = false; _pin.Width = colW; _pin.Height = 34;
        _pin.Font = new Font("Consolas", 8f); _pin.ForeColor = MutedC;
        _pin.TextAlign = ContentAlignment.MiddleCenter;
        Place(_pin, rightX, 277);

        Place(_clearPin, rightX, 319);

        // ---------------- About button (version · credits · license · repo) ----------------
        var about = new Button
        {
            Text = "About " + AppTitle,
            Width = 260, Height = 26, Top = 357,
            FlatStyle = FlatStyle.Flat, BackColor = Bg, ForeColor = MutedC,
        };
        about.FlatAppearance.BorderColor = MutedC;   // match the Clear-certificate button's lighter outline
        about.FlatAppearance.BorderSize = 1;
        about.Left = rightX + (colW - about.Width) / 2;
        about.Click += (_, __) => ShowAbout();
        Controls.Add(about);

        ApplyTheme();

        // load settings
        _name.Text = _settings.ReceiverName;
        _ip.Text = _settings.Ip;
        _port.Text = _settings.Port.ToString();
        _password.Text = _settings.Password;
        _source.SelectedIndex = _settings.Source == "mic" ? 1 : 0;
        _encrypt.Checked = _settings.UseTls;
        _compress.Checked = _settings.UseOpus;
        _video.Checked = _settings.UseVideo;
        _vWidth.Text = _settings.VideoMaxWidth.ToString();
        _vHeight.Text = _settings.VideoMaxHeight.ToString();
        _vBitrate.Text = _settings.VideoBitrateMbps.ToString();
        _vFps.Text = _settings.VideoFps.ToString();
        _cursor.Checked = _settings.ShowCursor;
        _incAudio.Checked = _settings.IncludeAudioWithVideo;
        ApplyVideoOptionState(_video.Checked);
        _bitrate.SelectedIndex = IndexForBitrate(_settings.OpusBitrate);
        _bitrate.Enabled = _compress.Checked;
        UpdatePinLabel();

        _startStop.Click += (_, __) => Toggle();
        _encrypt.CheckedChanged += (_, __) => { _settings.UseTls = _encrypt.Checked; _settings.Save(); };
        _compress.CheckedChanged += (_, __) =>
        {
            _settings.UseOpus = _compress.Checked;
            _bitrate.Enabled = _compress.Checked;
            _settings.Save();
        };
        _bitrate.SelectedIndexChanged += (_, __) =>
        {
            _settings.OpusBitrate = BitrateForIndex(_bitrate.SelectedIndex);
            _settings.Save();
        };
        _video.CheckedChanged += (_, __) =>
        {
            _settings.UseVideo = _video.Checked;
            ApplyVideoOptionState(_video.Checked);
            _settings.Save();
        };
        _cursor.CheckedChanged += (_, __) => { _settings.ShowCursor = _cursor.Checked; _settings.Save(); };
        _incAudio.CheckedChanged += (_, __) => { _settings.IncludeAudioWithVideo = _incAudio.Checked; _settings.Save(); };
        // Persist the (clamped) video knobs when focus leaves a field. The typed
        // text is left alone while editing; the final values are reflected back at
        // stream start (see Toggle → ReflectVideoFields).
        EventHandler persistVideo = (_, __) => PersistVideoFields();
        _vWidth.Leave += persistVideo;
        _vHeight.Leave += persistVideo;
        _vBitrate.Leave += persistVideo;
        _vFps.Leave += persistVideo;
        _clearPin.Click += (_, __) =>
        {
            _settings.PinnedFingerprint = "";
            _settings.Save();
            UpdatePinLabel();
        };
        FormClosing += (_, __) => { SaveSettings(); _streamer?.Stop(); };
    }

    private void TryLoadIcon()
    {
        try
        {
            var asm = System.Reflection.Assembly.GetExecutingAssembly();
            // Resource name is RootNamespace + filename.
            using var s = asm.GetManifestResourceStream("LanMediaSender.app.ico");
            if (s != null) Icon = new Icon(s);
        }
        catch { /* window just keeps the default icon */ }
    }

    private bool Running => _streamer != null;

    private void ApplyTheme()
    {
        foreach (var tb in new[] { _name, _ip, _port, _password, _vWidth, _vHeight, _vBitrate, _vFps })
        {
            tb.BackColor = PanelC; tb.ForeColor = TextC; tb.BorderStyle = BorderStyle.FixedSingle;
        }
        _vX.ForeColor = MutedC;
        foreach (var combo in new[] { _source, _bitrate })
        {
            combo.BackColor = PanelC; combo.ForeColor = TextC; combo.FlatStyle = FlatStyle.Flat;
        }
        _encrypt.ForeColor = TextC;
        _compress.ForeColor = TextC;
        _video.ForeColor = TextC;
        _cursor.ForeColor = TextC;
        _incAudio.ForeColor = TextC;
        _status.ForeColor = TextC;
        _pin.ForeColor = MutedC;

        _startStop.FlatStyle = FlatStyle.Flat;
        _startStop.FlatAppearance.BorderSize = 0;
        _startStop.ForeColor = Color.White;
        _startStop.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        StyleStartStop();

        _clearPin.FlatStyle = FlatStyle.Flat;
        _clearPin.BackColor = Bg;
        _clearPin.ForeColor = MutedC;
        _clearPin.FlatAppearance.BorderColor = MutedC;
        _clearPin.FlatAppearance.BorderSize = 1;

        _level.BarColor = AccentC;
        _level.TrackColor = PanelC;
    }

    /// <summary>Green when idle, red while streaming — matching the panel button.</summary>
    private void StyleStartStop()
    {
        Color c = Running ? DangerC : AccentC;
        _startStop.BackColor = c;
        _startStop.FlatAppearance.MouseOverBackColor = c;
        _startStop.FlatAppearance.MouseDownBackColor = c;
    }

    /// <summary>
    /// Enable/disable the video sub-options. The two checkboxes stay Enabled so
    /// their labels keep rendering (WinForms paints disabled text nearly black on
    /// this dark background); instead we lock them with AutoCheck=false and dim
    /// the text to a muted gray so it reads as "unavailable" rather than vanishing.
    /// </summary>
    private void ApplyVideoOptionState(bool on)
    {
        foreach (var tb in new[] { _vWidth, _vHeight, _vBitrate, _vFps })
        {
            tb.Enabled = on;
            tb.ForeColor = on ? TextC : DisabledC;
        }
        _vX.ForeColor = on ? MutedC : DisabledC;
        _cursor.AutoCheck = on;
        _incAudio.AutoCheck = on;
        _cursor.ForeColor = on ? TextC : DisabledC;
        _incAudio.ForeColor = on ? TextC : DisabledC;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Dark title bar on Windows 10 (20H1+) / 11. Harmless if unsupported.
        try
        {
            int on = 1;
            if (DwmSetWindowAttribute(Handle, 20, ref on, sizeof(int)) != 0)
                DwmSetWindowAttribute(Handle, 19, ref on, sizeof(int)); // older builds
        }
        catch { /* not supported on this OS build */ }
    }

    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    private static int BitrateForIndex(int i) => i switch
    {
        0 => 128000,
        1 => 192000,
        2 => 96000,
        3 => 64000,
        _ => 128000,
    };

    // ---- Video field validation: parse, clamp to a safe range, fall back to a
    //      sensible default on blank/garbage. Width/height are forced even (H.264). ----
    private static int ClampField(string text, int min, int max, int fallback, bool even = false)
    {
        if (!int.TryParse(text.Trim(), out int v)) v = fallback;
        v = Math.Clamp(v, min, max);
        if (even) v &= ~1;
        return v;
    }
    private int CurrentWidth()       => ClampField(_vWidth.Text, 320, 3840, 1920, even: true);
    private int CurrentHeight()      => ClampField(_vHeight.Text, 240, 2160, 1080, even: true);
    private int CurrentBitrateMbps() => ClampField(_vBitrate.Text, 1, 50, 10);
    private int CurrentFps()         => ClampField(_vFps.Text, 10, 60, 30);

    /// <summary>Write validated/clamped values back into the boxes so the user sees exactly what will stream.</summary>
    private void ReflectVideoFields()
    {
        _vWidth.Text = CurrentWidth().ToString();
        _vHeight.Text = CurrentHeight().ToString();
        _vBitrate.Text = CurrentBitrateMbps().ToString();
        _vFps.Text = CurrentFps().ToString();
    }

    /// <summary>Persist the clamped video knobs (called when a field loses focus).</summary>
    private void PersistVideoFields()
    {
        _settings.VideoMaxWidth = CurrentWidth();
        _settings.VideoMaxHeight = CurrentHeight();
        _settings.VideoFps = CurrentFps();
        _settings.VideoBitrateMbps = CurrentBitrateMbps();
        _settings.Save();
    }

    private static int IndexForBitrate(int b) => b switch
    {
        128000 => 0,
        192000 => 1,
        96000 => 2,
        64000 => 3,
        _ => 0,
    };

    private void UpdatePinLabel()
    {
        _pin.Text = string.IsNullOrEmpty(_settings.PinnedFingerprint)
            ? "No certificate pinned yet — it is trusted on first connect."
            : FormatPin(_settings.PinnedFingerprint);
    }

    /// <summary>Split a 32-byte fingerprint evenly across two lines (16 bytes each).</summary>
    private static string FormatPin(string fp)
    {
        var parts = fp.Split(':');
        if (parts.Length == 32)
            return string.Join(":", parts[..16]) + Environment.NewLine + string.Join(":", parts[16..]);
        return fp;
    }

    private void Toggle()
    {
        if (Running) { StopStreaming(); return; }

        string name = _name.Text.Trim();
        string ip = _ip.Text.Trim();
        if (name.Length == 0 && ip.Length == 0) { _status.Text = "Enter a receiver name or IP"; return; }
        int port = int.TryParse(_port.Text.Trim(), out int p) ? p : Protocol.DefaultPort;
        bool system = _source.SelectedIndex == 0;

        if (_video.Checked) ReflectVideoFields();   // show the exact clamped values that will stream
        SaveSettings();

        _streamer = _video.Checked
            ? new VideoStreamer(name, ip, port, _password.Text, _encrypt.Checked, _settings.PinnedFingerprint,
                CurrentWidth(), CurrentHeight(), CurrentFps(), (long)CurrentBitrateMbps() * 1_000_000,
                _cursor.Checked, _incAudio.Checked, BitrateForIndex(_bitrate.SelectedIndex))
            : new AudioStreamer(name, ip, port, _password.Text, system, _encrypt.Checked,
                _settings.PinnedFingerprint, _compress.Checked, BitrateForIndex(_bitrate.SelectedIndex));
        _streamer.Status += OnStatus;
        _streamer.Level += OnLevel;
        _streamer.Ended += OnEnded;
        _streamer.Pinned += OnPinned;
        try
        {
            _streamer.Start();
            _startStop.Text = "Stop streaming";
            StyleStartStop();
        }
        catch (Exception ex)
        {
            _status.Text = "Could not start: " + ex.Message;
            _streamer = null;
            StyleStartStop();
        }
    }

    private void StopStreaming()
    {
        _streamer?.Stop();
        _streamer = null;
        _startStop.Text = "Start streaming";
        _status.Text = "Stopped";
        _level.Value = 0;
        StyleStartStop();
    }

    /// <summary>Modal About dialog: version, credits, license, and repo link.</summary>
    private void ShowAbout()
    {
        using var dlg = new Form
        {
            Text = "About " + AppTitle,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            MaximizeBox = false, MinimizeBox = false, ShowInTaskbar = false,
            ClientSize = new Size(440, 348),
            BackColor = Bg, ForeColor = TextC, Font = new Font("Segoe UI", 9f),
        };

        const int x = 24, w = 392;

        Label L(string t, int top, int height, Color fore, Font? font = null) => new()
        {
            Text = t, Left = x, Top = top, Width = w, Height = height,
            AutoSize = false, ForeColor = fore, Font = font ?? dlg.Font,
            UseMnemonic = false,   // render '&' literally instead of as an accelerator
        };

        var title = L(AppTitle, 20, 30, TextC, new Font("Segoe UI", 15f, FontStyle.Bold));
        var ver = L("Version " + AppVersion, 54, 18, MutedC);
        var tagline = L("Windows to Android, LAN-based, screen & audio streaming", 74, 18, MutedC);
        var credits = L("Design Guidance and Testing by Mike Young\r\nProgrammed by Anthropic Claude Opus 4.8 High", 104, 40, TextC);

        var licenseHeader = L("License", 152, 18, MutedC, new Font("Segoe UI", 9f, FontStyle.Bold));
        var license = L(
            "Released under the MIT License. This software also bundles FFmpeg "
            + "(LGPL/GPL) and other open-source components, each under its own license. "
            + "See the repository for full license texts and third-party notices.",
            172, 72, TextC);

        var linkHeader = L("Project", 250, 18, MutedC, new Font("Segoe UI", 9f, FontStyle.Bold));
        var link = new LinkLabel
        {
            Text = GitHubUrl, Left = x, Top = 270, Width = w, Height = 18, AutoSize = false,
            LinkColor = AccentC, ActiveLinkColor = AccentC, VisitedLinkColor = AccentC,
            LinkBehavior = LinkBehavior.HoverUnderline, BackColor = Bg,
        };
        link.LinkClicked += (_, __) =>
        {
            try
            {
                System.Diagnostics.Process.Start(
                    new System.Diagnostics.ProcessStartInfo(GitHubUrl) { UseShellExecute = true });
            }
            catch { /* no default browser available */ }
        };

        var ok = new Button
        {
            Text = "Close", Width = 90, Height = 30,
            Left = dlg.ClientSize.Width - 90 - 24, Top = 300,
            FlatStyle = FlatStyle.Flat, BackColor = AccentC, ForeColor = Color.White,
            DialogResult = DialogResult.OK,
        };
        ok.FlatAppearance.BorderSize = 0;

        dlg.Controls.AddRange(new Control[]
            { title, ver, tagline, credits, licenseHeader, license, linkHeader, link, ok });
        dlg.AcceptButton = ok;
        dlg.CancelButton = ok;

        // Match the app's dark title bar (harmless if the OS build doesn't support it).
        dlg.HandleCreated += (_, __) =>
        {
            try { int on = 1; DwmSetWindowAttribute(dlg.Handle, 20, ref on, sizeof(int)); } catch { }
        };

        dlg.ShowDialog(this);
    }

    private void OnStatus(string s)
    {
        if (IsHandleCreated) BeginInvoke(() => _status.Text = s);
    }

    private void OnLevel(float peak)
    {
        if (IsHandleCreated)
            BeginInvoke(() => _level.Value = Math.Min(100, (int)(peak * 100f)));
    }

    private void OnEnded()
    {
        if (IsHandleCreated)
            BeginInvoke(() =>
            {
                _streamer = null;
                _startStop.Text = "Start streaming";
                _status.Text = "Stopped";
                _level.Value = 0;
                StyleStartStop();
            });
    }

    private void OnPinned(string fingerprint)
    {
        if (IsHandleCreated)
            BeginInvoke(() =>
            {
                _settings.PinnedFingerprint = fingerprint;
                _settings.Save();
                UpdatePinLabel();
            });
    }

    private void SaveSettings()
    {
        _settings.ReceiverName = _name.Text.Trim();
        _settings.Ip = _ip.Text.Trim();
        _settings.Port = int.TryParse(_port.Text.Trim(), out int p) ? p : Protocol.DefaultPort;
        _settings.Password = _password.Text;
        _settings.Source = _source.SelectedIndex == 1 ? "mic" : "system";
        _settings.UseTls = _encrypt.Checked;
        _settings.UseOpus = _compress.Checked;
        _settings.UseVideo = _video.Checked;
        _settings.VideoMaxWidth = CurrentWidth();
        _settings.VideoMaxHeight = CurrentHeight();
        _settings.VideoFps = CurrentFps();
        _settings.VideoBitrateMbps = CurrentBitrateMbps();
        _settings.ShowCursor = _cursor.Checked;
        _settings.IncludeAudioWithVideo = _incAudio.Checked;
        _settings.OpusBitrate = BitrateForIndex(_bitrate.SelectedIndex);
        _settings.Save();
    }
}
