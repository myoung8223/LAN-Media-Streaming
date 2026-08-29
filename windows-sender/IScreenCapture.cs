namespace LanMediaSender;

/// <summary>
/// A screen-capture source that hands out a pointer to the current desktop frame
/// as BGRA. Acquire()/Release() bracket each frame; the pointer is valid only
/// between them. GDI and DXGI implementations are interchangeable.
/// </summary>
internal interface IScreenCapture : IDisposable
{
    int Width { get; }
    int Height { get; }
    string Name { get; }   // "GPU (DXGI)" or "GDI", for the status line
    bool Lost { get; }     // capture became invalid and must be recreated

    /// <summary>Make the latest frame available. Returns false if none yet / lost.</summary>
    bool Acquire(out IntPtr bgra, out int stride);

    /// <summary>Release the frame from the last successful Acquire().</summary>
    void Release();
}
