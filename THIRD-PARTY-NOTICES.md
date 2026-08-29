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
| FFmpeg.AutoGen | LGPL-3.0 | https://github.com/Ruslan-B/FFmpeg.AutoGen |
| Vortice.Windows (Direct3D11, DXGI) | MIT | https://github.com/amerkoleci/Vortice.Windows |
| .NET runtime & Windows Forms | MIT | https://github.com/dotnet/runtime, https://github.com/dotnet/winforms |

### FFmpeg (bundled shared libraries)

The sender loads FFmpeg's shared libraries (`avcodec`, `avutil`, `swscale`, and
their dependencies) at runtime to encode H.264. FFmpeg is licensed under the
GNU Lesser General Public License (LGPL) version 2.1 or later; builds that
enable certain optional components are instead under the GNU General Public
License (GPL) version 2 or later. FFmpeg is © the FFmpeg developers.

  https://ffmpeg.org · https://www.ffmpeg.org/legal.html

FFmpeg is used via **dynamic linking** — it ships as separate `.dll` files that
can be replaced or rebuilt independently of this application. Redistributing it
alongside this MIT-licensed app is permitted under the LGPL provided you:

* keep FFmpeg's own license and copyright notices with the binaries;
* make the corresponding FFmpeg source available (or provide a written offer /
  link to the exact build you ship); and
* do not remove the user's ability to substitute a modified FFmpeg.

The FFmpeg DLLs are **not** included in this repository. See
`windows-sender/README.md` for where to obtain a compatible FFmpeg 7.1
"shared" build. If you distribute a package that bundles the FFmpeg binaries,
include that build's `COPYING.*`/`LICENSE.md` files and the matching source (or
a link to the exact version used).

## Android Receiver

| Component | License | Project |
|-----------|---------|---------|
| AndroidX Core KTX | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX AppCompat | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Material Components for Android | Apache-2.0 | https://github.com/material-components/material-components-android |

Opus audio and H.264 video are decoded with Android's built-in **MediaCodec**
framework (part of the operating system); no third-party codec libraries are
bundled in the receiver.

---

# License texts

## MIT License

Applies to **NAudio** (© Mark Heath and contributors), **Vortice.Windows**
(© Amer Koleci and contributors), and the **.NET runtime & Windows Forms**
(© .NET Foundation and contributors):

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

## BSD-3-Clause License — Concentus

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

## Apache License 2.0 — AndroidX & Material Components

The AndroidX and Material Components libraries are licensed under the Apache
License, Version 2.0. The full text is at:

  https://www.apache.org/licenses/LICENSE-2.0

Apache-2.0 requires that you (a) include a copy of the license with any
distribution, (b) retain all copyright, patent, trademark, and attribution
notices, and (c) include the contents of any `NOTICE` file shipped with the
components. For an Android app these libraries are pulled in at build time by
Gradle and packaged into the APK; if you distribute the APK, include a copy of
the Apache-2.0 license (e.g. bundle `licenses/Apache-2.0.txt`, downloaded from
the URL above) or surface it via an in-app "Open-source licenses" screen.

## LGPL — FFmpeg.AutoGen and FFmpeg

FFmpeg.AutoGen (LGPL-3.0) and the FFmpeg shared libraries (LGPL-2.1-or-later,
or GPL where so built) are used via dynamic linking. When you distribute
binaries that include them, include a copy of the applicable LGPL text
(https://www.gnu.org/licenses/lgpl-3.0.txt and
https://www.gnu.org/licenses/lgpl-2.1.txt) and satisfy the FFmpeg source-
availability requirement described in the FFmpeg section above.
