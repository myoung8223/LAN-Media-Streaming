using SharpGen.Runtime;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace LanMediaSender;

/// <summary>
/// GPU screen capture via DXGI Desktop Duplication (~2ms vs GDI's ~25-35ms).
/// Duplicates the primary output, copies to a CPU-readable staging texture, maps
/// it as BGRA, and (optionally) composites the mouse cursor — which duplication
/// delivers separately from the desktop image.
/// </summary>
internal sealed unsafe class DxgiScreenCapture : IScreenCapture
{
    private readonly bool _showCursor;

    private ID3D11Device _device = null!;
    private ID3D11DeviceContext _context = null!;
    private IDXGIOutputDuplication _dupl = null!;
    private ID3D11Texture2D _staging = null!;

    private bool _mapped;
    private bool _hasFrame;

    // cursor state
    private byte[]? _shape;
    private OutduplPointerShapeInfo _shapeInfo;
    private bool _haveShape;
    private int _ptrX, _ptrY;
    private bool _ptrVisible;

    public int Width { get; private set; }
    public int Height { get; private set; }
    public string Name => "GPU (DXGI)";
    public bool Lost { get; private set; }

    public DxgiScreenCapture(bool showCursor)
    {
        _showCursor = showCursor;

        D3D11.D3D11CreateDevice(
            null, DriverType.Hardware, DeviceCreationFlags.BgraSupport,
            null!, out _device!, out _context!).CheckError();

        using var dxgiDevice = _device.QueryInterface<IDXGIDevice>();
        using var adapter = dxgiDevice.GetAdapter();
        adapter.EnumOutputs(0, out IDXGIOutput outputTmp).CheckError();
        using var output = outputTmp;
        using var output1 = output.QueryInterface<IDXGIOutput1>();

        var rect = output.Description.DesktopCoordinates;
        Width = rect.Right - rect.Left;
        Height = rect.Bottom - rect.Top;

        _dupl = output1.DuplicateOutput(_device);

        var desc = new Texture2DDescription
        {
            Width = (uint)Width,
            Height = (uint)Height,
            MipLevels = 1,
            ArraySize = 1,
            Format = Format.B8G8R8A8_UNorm,
            SampleDescription = new SampleDescription(1, 0),
            Usage = ResourceUsage.Staging,
            BindFlags = BindFlags.None,
            CPUAccessFlags = CpuAccessFlags.Read | CpuAccessFlags.Write,
            MiscFlags = ResourceOptionFlags.None,
        };
        _staging = _device.CreateTexture2D(desc);
    }

    public bool Acquire(out IntPtr bgra, out int stride)
    {
        bgra = IntPtr.Zero; stride = 0;

        Result result = _dupl.AcquireNextFrame(15, out OutduplFrameInfo frameInfo, out IDXGIResource? desktopResource);

        if (result == Vortice.DXGI.ResultCode.WaitTimeout)
        {
            if (!_hasFrame) return false; // nothing captured yet
        }
        else if (result.Failure)
        {
            Lost = true;
            desktopResource?.Dispose();
            return false;
        }
        else
        {
            using (desktopResource)
            using (var tex = desktopResource!.QueryInterface<ID3D11Texture2D>())
            {
                _context.CopyResource(_staging, tex);
            }
            if (_showCursor) UpdateCursor(frameInfo);
            _dupl.ReleaseFrame();
            _hasFrame = true;
        }

        MappedSubresource map = _context.Map(_staging, 0, MapMode.ReadWrite, Vortice.Direct3D11.MapFlags.None);
        _mapped = true;
        bgra = map.DataPointer;
        stride = (int)map.RowPitch;

        if (_showCursor && _ptrVisible && _haveShape)
            DrawCursor((byte*)map.DataPointer, (int)map.RowPitch);

        return true;
    }

    private void UpdateCursor(OutduplFrameInfo frameInfo)
    {
        // PointerPosition is only valid on frames that carry a mouse update
        // (LastMouseUpdateTime != 0). Otherwise the mouse is still — keep the last
        // known position/visibility so the cursor stays drawn instead of blanking.
        if (frameInfo.LastMouseUpdateTime != 0)
        {
            _ptrVisible = frameInfo.PointerPosition.Visible;
            if (_ptrVisible)
            {
                _ptrX = frameInfo.PointerPosition.Position.X;
                _ptrY = frameInfo.PointerPosition.Position.Y;
            }
        }
        if (frameInfo.PointerShapeBufferSize > 0)
        {
            if (_shape == null || _shape.Length < frameInfo.PointerShapeBufferSize)
                _shape = new byte[frameInfo.PointerShapeBufferSize];
            fixed (byte* sp = _shape)
            {
                Result r = _dupl.GetFramePointerShape((uint)_shape.Length, (IntPtr)sp, out uint _, out _shapeInfo);
                _haveShape = r.Success;
            }
        }
    }

    /// <summary>Blend the cached cursor shape into the BGRA frame, clamped to bounds.</summary>
    private void DrawCursor(byte* dst, int dstStride)
    {
        byte[]? shape = _shape;
        if (shape == null) return;
        int type = (int)_shapeInfo.Type;
        int cw = (int)_shapeInfo.Width;
        int pitch = (int)_shapeInfo.Pitch;
        int ox = _ptrX, oy = _ptrY;

        fixed (byte* sp = shape)
        {
            if (type == 2) // DXGI_OUTDUPL_POINTER_SHAPE_TYPE_COLOR (BGRA, straight alpha)
            {
                int ch = (int)_shapeInfo.Height;
                for (int y = 0; y < ch; y++)
                {
                    int dy = oy + y; if (dy < 0 || dy >= Height) continue;
                    for (int x = 0; x < cw; x++)
                    {
                        int dx = ox + x; if (dx < 0 || dx >= Width) continue;
                        byte* s = sp + y * pitch + x * 4;
                        int a = s[3]; if (a == 0) continue;
                        byte* d = dst + dy * dstStride + dx * 4;
                        d[0] = (byte)((s[0] * a + d[0] * (255 - a)) / 255);
                        d[1] = (byte)((s[1] * a + d[1] * (255 - a)) / 255);
                        d[2] = (byte)((s[2] * a + d[2] * (255 - a)) / 255);
                    }
                }
            }
            else if (type == 1) // MONOCHROME (1bpp: top AND mask, bottom XOR mask)
            {
                int ch = (int)_shapeInfo.Height / 2;
                for (int y = 0; y < ch; y++)
                {
                    int dy = oy + y; if (dy < 0 || dy >= Height) continue;
                    for (int x = 0; x < cw; x++)
                    {
                        int dx = ox + x; if (dx < 0 || dx >= Width) continue;
                        int bit = 7 - (x & 7);
                        int andM = (sp[y * pitch + (x >> 3)] >> bit) & 1;
                        int xorM = (sp[(y + ch) * pitch + (x >> 3)] >> bit) & 1;
                        byte* d = dst + dy * dstStride + dx * 4;
                        if (andM == 0 && xorM == 0) { d[0] = d[1] = d[2] = 0; }          // black
                        else if (andM == 0 && xorM == 1) { d[0] = d[1] = d[2] = 255; }   // white
                        else if (andM == 1 && xorM == 1) { d[0] = (byte)(255 - d[0]); d[1] = (byte)(255 - d[1]); d[2] = (byte)(255 - d[2]); } // invert
                        // (1,0) → transparent
                    }
                }
            }
            else if (type == 4) // MASKED_COLOR (alpha 0 = copy RGB, 0xFF = XOR with screen)
            {
                int ch = (int)_shapeInfo.Height;
                for (int y = 0; y < ch; y++)
                {
                    int dy = oy + y; if (dy < 0 || dy >= Height) continue;
                    for (int x = 0; x < cw; x++)
                    {
                        int dx = ox + x; if (dx < 0 || dx >= Width) continue;
                        byte* s = sp + y * pitch + x * 4;
                        byte* d = dst + dy * dstStride + dx * 4;
                        if (s[3] == 0) { d[0] = s[0]; d[1] = s[1]; d[2] = s[2]; }
                        else { d[0] ^= s[0]; d[1] ^= s[1]; d[2] ^= s[2]; }
                    }
                }
            }
        }
    }

    public void Release()
    {
        if (_mapped) { _context.Unmap(_staging, 0); _mapped = false; }
    }

    public void Dispose()
    {
        try { if (_mapped) _context.Unmap(_staging, 0); } catch { }
        _staging?.Dispose();
        _dupl?.Dispose();
        _context?.Dispose();
        _device?.Dispose();
    }
}
