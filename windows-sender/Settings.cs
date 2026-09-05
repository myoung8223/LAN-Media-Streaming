using System.Text.Json;

namespace LanMediaSender;

/// <summary>Persisted UI settings, stored under %AppData%\LanMediaSender\settings.json.</summary>
internal class Settings
{
    public string ReceiverName { get; set; } = "";
    public string Ip { get; set; } = "";
    public int Port { get; set; } = Protocol.DefaultPort;
    public string Password { get; set; } = "";
    public string Source { get; set; } = "system"; // "system" | "mic"
    public bool UseTls { get; set; } = true;
    public string PinnedFingerprint { get; set; } = "";
    public bool UseOpus { get; set; } = true;
    public int OpusBitrate { get; set; } = 128000; // bits/sec
    public bool UseVideo { get; set; } = false;     // experimental screen-video mode
    public int VideoMaxWidth { get; set; } = 1920;   // output cap (width); paired with height as a bounding box
    public int VideoMaxHeight { get; set; } = 1080;  // output cap; downscales larger screens, never upscales
    public int VideoFps { get; set; } = 30;
    public int VideoBitrateMbps { get; set; } = 10;  // H.264 target bitrate (megabits/sec)
    public bool ShowCursor { get; set; } = true;     // composite the mouse cursor into video
    public bool IncludeAudioWithVideo { get; set; } = true; // mux system audio alongside video

    private static string PathFile()
    {
        string dir = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "LanMediaSender");
        System.IO.Directory.CreateDirectory(dir);
        return System.IO.Path.Combine(dir, "settings.json");
    }

    public static Settings Load()
    {
        try
        {
            string p = PathFile();
            if (System.IO.File.Exists(p))
                return JsonSerializer.Deserialize<Settings>(System.IO.File.ReadAllText(p)) ?? new Settings();
        }
        catch { /* ignore */ }
        return new Settings();
    }

    public void Save()
    {
        try
        {
            System.IO.File.WriteAllText(PathFile(),
                JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* ignore */ }
    }
}
