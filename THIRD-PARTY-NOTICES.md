# Third-Party Notices

LAN Media Sender and LAN Media Receiver are released under the MIT License (see
`LICENSE`). They use the third-party components listed below, each of which
remains under its own license. This file reproduces the required notices so they
travel with both the source and any binary distribution.

## Windows Sender (.NET)

| Component | License | Project |
|-----------|---------|---------|
| NAudio | MIT | https://github.com/naudio/NAudio |
| Concentus | BSD-3-Clause | https://github.com/lostromb/concentus |
| FFmpeg.AutoGen | MIT | https://github.com/Ruslan-B/FFmpeg.AutoGen |
| Vortice.Windows (Direct3D11, DXGI) | MIT | https://github.com/amerkoleci/Vortice.Windows |
| .NET runtime & Windows Forms | MIT | https://github.com/dotnet/runtime, https://github.com/dotnet/winforms |

### FFmpeg (required at runtime, not distributed)

The sender loads FFmpeg's shared libraries (`avcodec`, `avutil`, `swscale`, and
their dependencies) at runtime to encode H.264, but this project does **not**
include or distribute them — the user downloads a compatible FFmpeg build
separately (see `windows-sender/README.md`). Because FFmpeg is not shipped with
this project's source or its release binaries, its LGPL/GPL redistribution
terms are not triggered here. FFmpeg is © the FFmpeg developers and is licensed
under the LGPL-2.1-or-later (some builds GPL) — https://ffmpeg.org/legal.html.

## Android Receiver

| Component | License | Project |
|-----------|---------|---------|
| AndroidX Core KTX | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX AppCompat | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Material Components for Android | Apache-2.0 | https://github.com/material-components/material-components-android |

Opus audio and H.264 video are decoded with Android's built-in **MediaCodec**
framework (part of the operating system); no third-party codec libraries are
bundled in the receiver.

## Linux Receiver

The Linux receiver is distributed as **source only**. The libraries below are
**not** included in this repository; they are installed from the distribution's
package manager (e.g. `apt`) on the build machine and linked at build time — so,
as with the Windows sender's FFmpeg, this project does not redistribute them and
their redistribution terms are not triggered by the source alone. A binary you
compile links these system-provided libraries dynamically rather than bundling
them; if you choose to bundle any of them with a binary you distribute, include
that library's license text (and, for the LGPL components, preserve the LGPL's
ability for a user to relink against their own build).

| Component | License | Project |
|-----------|---------|---------|
| SDL2 (Simple DirectMedia Layer) | Zlib | https://www.libsdl.org |
| FFmpeg — libavcodec, libavutil, libswscale | LGPL-2.1-or-later (some builds GPL) | https://ffmpeg.org |
| libopus | BSD-3-Clause | https://opus-codec.org |
| OpenSSL (3.x) | Apache-2.0 | https://www.openssl.org |
| GTK 3 & GLib (settings app only) | LGPL-2.1-or-later | https://www.gtk.org |

H.264 is decoded with FFmpeg's software decoder or, when the GPU and driver
support it, VAAPI (Intel/AMD) hardware decode via libavcodec. VAAPI itself
(`libva`) and its GPU drivers are provided by the operating system and are not
bundled with this project.

---

# License texts

## MIT License

Applies to **NAudio** (© Mark Heath and contributors), **Vortice.Windows**
(© Amer Koleci and contributors), **FFmpeg.AutoGen** (© Ruslan Balanukhin and
contributors), and the **.NET runtime & Windows Forms** (© .NET Foundation and
contributors):

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## BSD-3-Clause License — Concentus & libopus

```
Copyright (c) Xiph.Org Foundation, Skype Limited, CSIRO, Microsoft Corporation,
Jean-Marc Valin, Timothy B. Terriberry, Gregory Maxwell, Mark Borgerding, and
Logan Stromberg.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

- Neither the name of the copyright holders nor the names of contributors may
  be used to endorse or promote products derived from this software without
  specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

> This reproduces Concentus's BSD-3-Clause notice. The authoritative text is the
> `LICENSE` file in the Concentus repository; if you bundle Concentus binaries,
> keeping a verbatim copy of that file alongside them is the safest practice.

> **libopus** (used by the Linux receiver) is covered by the same BSD-3-Clause
> license, © the Xiph.Org Foundation, Jean-Marc Valin, and contributors. On
> Linux it is installed from the OS package manager rather than bundled; its
> authoritative `COPYING` file ships with that package.

## Zlib License — SDL2

```
Copyright (C) 1997-2024 Sam Lantinga <slouken@libsdl.org>

This software is provided 'as-is', without any express or implied warranty. In
no event will the authors be held liable for any damages arising from the use of
this software.

Permission is granted to anyone to use this software for any purpose, including
commercial applications, and to alter it and redistribute it freely, subject to
the following restrictions:

1. The origin of this software must not be misrepresented; you must not claim
   that you wrote the original software. If you use this software in a product,
   an acknowledgment in the product documentation would be appreciated but is
   not required.
2. Altered source versions must be plainly marked as such, and must not be
   misrepresented as being the original software.
3. This notice may not be removed or altered from any source distribution.
```

> This reproduces SDL2's zlib license. The authoritative text is SDL's own
> `LICENSE.txt`. SDL2 is installed from the OS package manager on Linux and is
> not bundled with this project.

## Apache License 2.0 — AndroidX, Material Components & OpenSSL 3.x

The AndroidX and Material Components libraries (Android receiver) and **OpenSSL
3.x** (Linux receiver's TLS) are licensed under the Apache License, Version 2.0.
The full text is at:

  https://www.apache.org/licenses/LICENSE-2.0

Apache-2.0 requires that you (a) include a copy of the license with any
distribution, (b) retain all copyright, patent, trademark, and attribution
notices, and (c) include the contents of any `NOTICE` file shipped with the
components. For an Android app these libraries are pulled in at build time by
Gradle and packaged into the APK; if you distribute the APK, include a copy of
the Apache-2.0 license (e.g. bundle `licenses/Apache-2.0.txt`, downloaded from
the URL above) or surface it via an in-app "Open-source licenses" screen.

OpenSSL 3.x on Linux is installed from the OS package manager and linked by the
receiver; it is not bundled with this project. Its authoritative `LICENSE.txt`
(Apache-2.0) ships with that package. If you distribute a binary that bundles
OpenSSL, include a copy of the Apache-2.0 license and OpenSSL's `NOTICE`/
copyright lines with it.

## FFmpeg (not included — user-supplied)

This project does **not** distribute FFmpeg. The Windows sender loads FFmpeg's
shared libraries at runtime and the Linux receiver links them, but in both cases
the user obtains FFmpeg separately — a compatible build placed next to the
Windows executable, or the distribution's packages on Linux (see each app's
README). Because FFmpeg is not shipped with this project's source or its release
binaries, its own redistribution terms are not triggered here. FFmpeg is
licensed under the LGPL-2.1-or-later (some builds GPL) and remains © the FFmpeg
developers — https://ffmpeg.org/legal.html.

## LGPL-2.1-or-later — GTK 3 & GLib (Linux settings app)

The Linux receiver's settings app links GTK 3 and GLib, licensed under the GNU
Lesser General Public License, version 2.1 or later:

  https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

These libraries are installed from the OS package manager and linked
dynamically; this project neither modifies nor bundles them. For a binary that
links them, the LGPL is satisfied by the dynamic link to the system-provided
libraries (which lets a user substitute their own build) together with this
notice and a copy of, or pointer to, the LGPL text. The streaming daemon itself
does not link GTK — only the separate settings program does.
