using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace LanMediaSender;

/// <summary>
/// Fallback screen capture using GDI (Graphics.CopyFromScreen). Slower than DXGI
/// but works everywhere. Optionally draws the mouse cursor (GDI omits it).
/// </summary>
internal sealed class GdiScreenCapture : IScreenCapture
{
    private readonly Rectangle _bounds;
    private readonly bool _showCursor;
    private readonly Bitmap _bmp;
    private readonly Graphics _g;
    private BitmapData? _locked;

    public int Width { get; }
    public int Height { get; }
    public string Name => "GDI";
    public bool Lost => false;

    public GdiScreenCapture(bool showCursor)
    {
        _showCursor = showCursor;
        _bounds = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1920, 1080);
        Width = _bounds.Width & ~1;
        Height = _bounds.Height & ~1;
        _bmp = new Bitmap(Width, Height, PixelFormat.Format32bppArgb);
        _g = Graphics.FromImage(_bmp);
    }

    public bool Acquire(out IntPtr bgra, out int stride)
    {
        _g.CopyFromScreen(_bounds.X, _bounds.Y, 0, 0, new Size(Width, Height), CopyPixelOperation.SourceCopy);
        if (_showCursor) DrawCursor();

        _locked = _bmp.LockBits(new Rectangle(0, 0, Width, Height), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        bgra = _locked.Scan0;
        stride = _locked.Stride;
        return true;
    }

    private void DrawCursor()
    {
        var ci = new CURSORINFO { cbSize = Marshal.SizeOf<CURSORINFO>() };
        if (!GetCursorInfo(ref ci) || (ci.flags & CURSOR_SHOWING) == 0 || ci.hCursor == IntPtr.Zero) return;

        int hotX = 0, hotY = 0;
        if (GetIconInfo(ci.hCursor, out ICONINFO ii))
        {
            hotX = ii.xHotspot; hotY = ii.yHotspot;
            if (ii.hbmMask != IntPtr.Zero) DeleteObject(ii.hbmMask);
            if (ii.hbmColor != IntPtr.Zero) DeleteObject(ii.hbmColor);
        }

        IntPtr hdc = _g.GetHdc();
        try
        {
            DrawIconEx(hdc, ci.ptScreenPos.x - _bounds.X - hotX, ci.ptScreenPos.y - _bounds.Y - hotY,
                       ci.hCursor, 0, 0, 0, IntPtr.Zero, DI_NORMAL);
        }
        finally
        {
            _g.ReleaseHdc(hdc);
        }
    }

    public void Release()
    {
        if (_locked != null) { _bmp.UnlockBits(_locked); _locked = null; }
    }

    public void Dispose()
    {
        try { if (_locked != null) _bmp.UnlockBits(_locked); } catch { }
        try { _g.Dispose(); } catch { }
        try { _bmp.Dispose(); } catch { }
    }

    // ---- Win32 cursor interop ----
    private const int CURSOR_SHOWING = 0x0001;
    private const int DI_NORMAL = 0x0003;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int x; public int y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct CURSORINFO { public int cbSize; public int flags; public IntPtr hCursor; public POINT ptScreenPos; }

    [StructLayout(LayoutKind.Sequential)]
    private struct ICONINFO { public bool fIcon; public int xHotspot; public int yHotspot; public IntPtr hbmMask; public IntPtr hbmColor; }

    [DllImport("user32.dll")] private static extern bool GetCursorInfo(ref CURSORINFO pci);
    [DllImport("user32.dll")] private static extern bool GetIconInfo(IntPtr hIcon, out ICONINFO piconinfo);
    [DllImport("user32.dll")] private static extern bool DrawIconEx(IntPtr hdc, int x, int y, IntPtr hIcon,
        int cxWidth, int cyWidth, int istepIfAniCur, IntPtr hbrFlickerFreeDraw, int diFlags);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr hObject);
}
