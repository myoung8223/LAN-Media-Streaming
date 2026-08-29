using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace LanMediaSender;

/// <summary>
/// Resolves a panel's current IP + audio port by name over a tiny UDP broadcast,
/// so DHCP changes and multiple panels are handled without a hard-coded address.
///
/// Query  (broadcast → :45789): {"magic":"LANDISC1","q":"<name>"}
/// Reply  (panel → unicast):     {"magic":"LANDISC1","name":"Room 214","port":45788,"tls":true}
///
/// The panel's IP is taken from the reply's source address (not embedded), so it
/// is always the address the reply actually came from.
/// </summary>
internal static class Discovery
{
    public sealed record Result(string Name, string Ip, int Port, bool Tls);

    /// <summary>
    /// Broadcast for <paramref name="name"/> and return the first matching panel,
    /// or null if none answers within <paramref name="timeoutMs"/>.
    /// </summary>
    public static Result? Resolve(string name, int timeoutMs = 1500)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;

        try
        {
            using var udp = new UdpClient(AddressFamily.InterNetwork);
            udp.EnableBroadcast = true;
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, 0));

            string query = "{\"magic\":\"" + Protocol.DiscoveryMagic + "\",\"q\":\"" + JsonEscape(name) + "\"}\n";
            byte[] q = Encoding.UTF8.GetBytes(query);
            var dest = new IPEndPoint(IPAddress.Broadcast, Protocol.DiscoveryPort);
            udp.Send(q, q.Length, dest);

            var deadline = Environment.TickCount + timeoutMs;
            while (true)
            {
                int remaining = deadline - Environment.TickCount;
                if (remaining <= 0) return null;
                udp.Client.ReceiveTimeout = remaining;

                IPEndPoint from = new(IPAddress.Any, 0);
                byte[] data;
                try { data = udp.Receive(ref from); }
                catch (SocketException) { return null; } // timed out

                Result? r = TryParse(data, from, name);
                if (r != null) return r;
                // otherwise keep waiting for a matching reply until the deadline
            }
        }
        catch
        {
            return null; // discovery is best-effort; caller falls back to a manual IP
        }
    }

    private static Result? TryParse(byte[] data, IPEndPoint from, string wantName)
    {
        try
        {
            using var doc = JsonDocument.Parse(Encoding.UTF8.GetString(data).Trim());
            var root = doc.RootElement;
            if (!root.TryGetProperty("magic", out var m) || m.GetString() != Protocol.DiscoveryMagic)
                return null;

            string name = root.TryGetProperty("name", out var nEl) ? (nEl.GetString() ?? "") : "";
            if (!string.Equals(name, wantName, StringComparison.OrdinalIgnoreCase)) return null;

            int port = root.TryGetProperty("port", out var pEl) && pEl.TryGetInt32(out int pv)
                ? pv : Protocol.DefaultPort;
            bool tls = root.TryGetProperty("tls", out var tEl) && tEl.ValueKind == JsonValueKind.True;

            return new Result(name, from.Address.ToString(), port, tls);
        }
        catch { return null; }
    }

    private static string JsonEscape(string s)
    {
        var sb = new StringBuilder(s.Length + 4);
        foreach (char c in s)
        {
            switch (c)
            {
                case '\\': sb.Append("\\\\"); break;
                case '"': sb.Append("\\\""); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (c < 0x20) sb.Append("\\u").Append(((int)c).ToString("x4"));
                    else sb.Append(c);
                    break;
            }
        }
        return sb.ToString();
    }
}
