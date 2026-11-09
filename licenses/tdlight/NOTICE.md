# TDLight and native dependency notices

Telegram MCP Server distributes the upstream TDLight Java wrapper and its API
and JNI JARs. The files in `upstream/` reproduce the upstream license and
attribution texts. These components retain their own license terms.

| Bundled component | License and attribution files | Corresponding source |
| --- | --- | --- |
| TDLight Java `3.5.9+td.1.8.68` | `TDLight-Java-LGPL-3.0.txt`, `GPL-3.0.txt`, `JTDLib-LICENSE.txt` | [TDLight Java source at `e1a240e`](https://github.com/tdlight-team/tdlight-java/tree/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae), [source archive](https://github.com/tdlight-team/tdlight-java/archive/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae.tar.gz) |
| TDLight API `4.0.565` | `TDLight-JNI-BSL-1.0.txt`, `TDLight-BSL-1.0.txt` | [API generation source at `3388b55`](https://github.com/tdlight-team/tdlight-java-natives/tree/3388b55e003ca76e94fbb18b72379ed9c6166421), [generation run 565](https://github.com/tdlight-team/tdlight-java-natives/actions/runs/39285519512), [TDLight API definition at `d671a00`](https://github.com/tdlight-team/tdlight/tree/d671a00b1213b0d5fccbd7010ca8db0c3582004d) |
| TDLight JNI `4.0.597`, all four classifiers | `TDLight-JNI-BSL-1.0.txt`, `TDLight-BSL-1.0.txt`, `SQLite-LICENSE.txt` | [JNI build source at `70a9449`](https://github.com/tdlight-team/tdlight-java-natives/tree/70a9449a2c5b322e09b6d0ddd1b33cc79a8db068), [native build run 597](https://github.com/tdlight-team/tdlight-java-natives/actions/runs/39320905633), [TDLight and embedded SQLite source at `ccf8956`](https://github.com/tdlight-team/tdlight/tree/ccf8956e63d29074009867859ecad251c75f36e1) |
| OpenSSL `3.5.2`, statically linked in Windows JNI | `OpenSSL-LICENSE.txt`, `OpenSSL-ACKNOWLEDGEMENTS.md` | [OpenSSL source](https://github.com/openssl/openssl/tree/openssl-3.5.2), [vcpkg OpenSSL port](https://github.com/microsoft/vcpkg/tree/b1e15efef6758eaa0beb0a8732cfa66f6a68a81d/ports/openssl) |
| OpenSSL `3.6.3`, statically linked in macOS JNI | `OpenSSL-LICENSE.txt`, `OpenSSL-ACKNOWLEDGEMENTS.md` | [OpenSSL source](https://github.com/openssl/openssl/tree/openssl-3.6.3), [macOS build recipe](https://github.com/tdlight-team/tdlight-java-natives/blob/70a9449a2c5b322e09b6d0ddd1b33cc79a8db068/.github/workflows/scripts/build_macos_arm64.sh) |
| zlib `1.3.1`, statically linked in Windows JNI | `zlib-LICENSE.txt` | [zlib source](https://github.com/madler/zlib/tree/v1.3.1), [vcpkg zlib port](https://github.com/microsoft/vcpkg/tree/b1e15efef6758eaa0beb0a8732cfa66f6a68a81d/ports/zlib) |

The API generator and native build repositories contain their build recipes
and submodule pins. To obtain those inputs, check out the listed commits and
initialize the submodules. The Windows job uses the vcpkg revision linked
above with the `x64-windows-static` triplet. The OpenSSL license and
acknowledgments are byte-identical in the two bundled OpenSSL releases.

Both Linux classifiers dynamically link `libssl.so.3`, `libcrypto.so.3`, and
`libz.so.1`. The macOS classifier dynamically links the platform zlib. The
host or container distribution supplies these libraries and their notices.
The OpenSSL `3.0.22` text in the Linux binaries identifies the build headers.
The runtime OpenSSL version comes from the host or container distribution.

The GNU GPL text comes from [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.txt)
and accompanies the LGPL text. The SQLite notice is taken from
`sqlite/sqlite/LICENSE` in the pinned TDLight source.

## Using a modified TDLight library

The server uses a separate TDLight library on the Java classpath. Obtain the
runnable server JAR from the release, the application image, or `/app/app.jar`
in the container image. With JDK 25, extract it in an empty working directory:

```sh
jar xf /path/to/telegram-mcp-server.jar BOOT-INF/classes BOOT-INF/lib META-INF/build-info.properties
```

Replace the relevant TDLight JAR in `BOOT-INF/lib` with a compatible modified
JAR, keeping one copy of each library on the classpath. To launch the server
with the replacement:

```sh
java --enable-native-access=ALL-UNNAMED -cp '.:BOOT-INF/classes:BOOT-INF/lib/*' \
  dev.telegrammcp.server.TelegramMcpApplicationKt serve --transport stdio
```

On Windows, use `.;BOOT-INF/classes;BOOT-INF/lib/*` as the classpath. The
server's configuration and data paths can be supplied as for the normal
launcher. Application source is available from the release tag in the
[Telegram MCP Server repository](https://github.com/tolboy/telegram-mcp-tdlib).
