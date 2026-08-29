namespace LanMediaSender;

/// <summary>
/// Common surface for the audio-only and video streamers so the form can drive
/// either one interchangeably.
/// </summary>
internal interface IStreamer
{
    event Action<string>? Status;
    event Action<float>? Level;   // 0..1 meter (audio); video may leave it at 0
    event Action? Ended;
    event Action<string>? Pinned; // cert fingerprint on trust-on-first-use

    void Start();
    void Stop();
}
