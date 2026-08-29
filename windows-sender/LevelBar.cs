using System.Drawing;
using System.Windows.Forms;

namespace LanMediaSender;

/// <summary>
/// A tiny flat level meter we can color to match the Android app's theme
/// (the stock WinForms ProgressBar ignores custom colors under visual styles).
/// </summary>
internal sealed class LevelBar : Control
{
    private int _value;

    /// <summary>0..100.</summary>
    public int Value
    {
        get => _value;
        set
        {
            int v = value < 0 ? 0 : (value > 100 ? 100 : value);
            if (v != _value) { _value = v; Invalidate(); }
        }
    }

    public Color BarColor { get; set; } = Color.FromArgb(0x2E, 0xCC, 0x9B);
    public Color TrackColor { get; set; } = Color.FromArgb(0x17, 0x1C, 0x24);

    public LevelBar()
    {
        DoubleBuffered = true;
        SetStyle(ControlStyles.ResizeRedraw, true);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        using (var track = new SolidBrush(TrackColor))
            g.FillRectangle(track, ClientRectangle);

        int w = (int)(ClientRectangle.Width * (_value / 100f));
        if (w > 0)
            using (var bar = new SolidBrush(BarColor))
                g.FillRectangle(bar, 0, 0, w, ClientRectangle.Height);
    }
}
