using System.Security.Cryptography;
using System.Text;

namespace LanMediaSender;

/// <summary>Shared constants + helpers for LAN Media wire protocol v1.</summary>
internal static class Protocol
{
    public const string Magic = "LANMED01";
    public const int Version = 2;         // audio-only handshake version
    public const int VideoVersion = 3;    // v3 adds a muxed video (+audio) stream
    public const int DefaultPort = 45788;

    /// <summary>UDP name-discovery (fixed, independent of the audio port).</summary>
    public const int DiscoveryPort = 45789;
    public const string DiscoveryMagic = "LANDISC1";

    // v3 muxed framing: [type:1][ptsMs:8 BE][len:4 BE][payload].
    public const byte StreamAudio = 0;
    public const byte StreamVideo = 1;

    /// <summary>Lowercase hex SHA-256 of the password; empty string in -> empty out.</summary>
    public static string Sha256Hex(string? s)
    {
        if (string.IsNullOrEmpty(s)) return "";
        byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(s));
        var sb = new StringBuilder(hash.Length * 2);
        foreach (byte b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }
}
