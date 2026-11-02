# Live terminal recording

The [README GIF](assets/live-demo-v1.19.0.gif) is a screen recording of an actual Windows
console window, including its title bar. It was recorded on November 2, 2026
with FFmpeg's `gdigrab` screen-capture input. The recording is approximately
17 seconds long, at its original speed, with no cuts. GIF conversion reduces
the frame rate from 12 to 8 fps. No frames or terminal output are redrawn.

Published asset filenames include the recorded server version so a new recording
gets a new URL and does not reuse an earlier recording's browser cache.

The terminal runs [scripts/readme-demo.py](../scripts/readme-demo.py), a small
Python MCP SDK client, against the released server 1.19.0 and an existing
authorized TDLib user-account session. This demonstrates the real MCP protocol
and Telegram connection. It does not include an AI assistant's conversation.

The JAR was downloaded from the [v1.19.0 release](https://github.com/tolboy/telegram-mcp-tdlib/releases/tag/v1.19.0)
and its SHA-256 matched the release asset digest:
`93c5a6f98cf6ad5f3270618eef132b0185196bd170e28320c96dcec604d5f354`.
The client was run with `--expect-version 1.19.0`, which verifies the version
reported by the live MCP handshake before proceeding.

The command runs these operations and prints their results as they complete:

1. `initialize` and `tools/list`: 36 tools, all with `readOnlyHint=true`;
   `send_message` is absent.
2. `resolve_username`: resolve the public `@telegram` channel to Telegram News.
3. `get_history`, `limit: 1`: retrieve a channel post and print selected public
   response fields, a text excerpt, and the public message link.

The client prints actual measured tool-call times. The post's timestamp belongs
to the returned message, independently of the date of this recording. The
visible `demo.py` and `server.jar` are local short names for the repository's
demo script and the released JAR.

- [MP4 screen recording](assets/live-demo-v1.19.0.mp4)
- [PNG frame extracted from the recording](assets/live-demo-v1.19.0.png)
- [Public response transcript from this run](assets/live-demo-v1.19.0.json)

## Keep personal data out of the recording

This capture uses only a public channel. The account label is the local alias
`demo`; credentials come from the child process environment and are never
printed. Server stderr goes to a separate local temporary directory. The shell
prompt is set to `>` with `prompt $G`, so the home-directory path does not appear.
Only the console window's screen area is captured. No blur or other redaction
was needed for this run.

For a recording involving personal chats, review the entire exported video,
including scrolling, window titles, notifications, and error messages. Redact
the video before exporting the README GIF. Keep private source recordings out
of Git.

## Run the terminal demonstration

```powershell
python -m venv build/readme-demo/venv
build/readme-demo/venv/Scripts/python.exe -m pip install -r scripts/requirements-mcp-client.txt
```

Supply the same environment variables you use for an authorized server,
including API credentials and the absolute TDLib database directory. The
script inherits the environment; it does not load `.env` or client settings.
Stop other processes owning that TDLib session before running it. The script
starts one STDIO server and closes it when finished. It forces the `inbox`
profile, read-only mode, and disabled downloads in its child server.

```powershell
build/readme-demo/venv/Scripts/python.exe scripts/readme-demo.py --jar /absolute/path/to/telegram-mcp-server.jar --account default --channel '@telegram' --expect-version 1.19.0
```

Use your configured account label for `--account`. The public JSON transcript
defaults to the ignored `build/readme-demo/capture/` directory. The script
fails if a tool call fails, a writable tool is exposed, the target does not
resolve to a channel, or the history is empty. It calls no messaging, draft,
subscription, or read-receipt tools. No image-rendering library is needed.

## Record and export

For a manual Windows capture, use OBS Studio's
[Window Capture](https://obsproject.com/kb/window-capture-sources) source,
select the terminal window, and record to MKV. Review the recording, then
convert the screen frames with FFmpeg:

```powershell
ffmpeg -i recording.mkv -filter_complex '[0:v]fps=8,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle' -loop 0 live-demo.gif
ffmpeg -sseof -1 -i recording.mkv -frames:v 1 live-demo.png
```

For a terminal-data recording instead of screen capture,
[asciinema](https://docs.asciinema.org/getting-started/) records terminal output
and [agg](https://docs.asciinema.org/manual/agg/usage/) exports it to GIF.
The checked-in demonstration uses captured screen pixels.
