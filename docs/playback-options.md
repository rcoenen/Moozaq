# Playback options research

Researched October 16, 2026. Requirement: ordinary user-supplied YouTube links, no YouTube Premium, Nest playback that can continue without the Android phone, and reconnection with remote controls. This research does not establish that extraction or playback succeeds for the six starter links.

## Technical options

| Option | Phone can leave? | Assessment |
|---|---|---|
| Android audio mirroring or Bluetooth | No | Phone remains the source; does not meet preferred behavior. |
| Download audio on phone and serve it to Nest over HTTP | No, while served from phone | Technically plausible, but downloaded data remains on the phone. Casting a URL does not imply the entire file is copied into Nest. |
| Resolve an audio URL and hand it directly to Nest | Potentially | Best no-server hypothesis. Must test receiver access, required headers, tokens, URL expiry, seeking and long playback. |
| Always-on Mac/home server resolves and relays audio | Yes, if server remains available | Strongest experimental fit for independent playback. Requires ongoing upstream access; streaming and seeking need careful handling. |
| Always-on server caches a complete audio file then serves it | Yes, if server remains available | More predictable seeking and playback after acquisition; startup wait and storage costs. Same extraction/permission constraints apply. |
| Phone downloads then uploads to a home/cloud server | Yes, after upload | Possible but adds a transfer; server-side acquisition avoids this extra phone work. |
| Creator-provided downloadable or licensed audio | Yes with receiver-accessible hosting | Supported content foundation, but not a general solution for arbitrary YouTube links. |

These are architecture assessments, not demonstrated results. A streaming server can start playback before the full mix is acquired, but seeking into unavailable portions, container metadata and upstream renewal complicate that implementation. For a first extraction experiment, a fully acquired file separates acquisition failures from delivery failures.

## Findings and sources

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) supports audio-only format selection. When the source exposes a usable audio-only format, downloading the full video and stripping it is unnecessary. Conversion is needed only if the selected media is incompatible with the intended receiver/container.
- [Google Cast media support](https://developers.google.com/cast/docs/media) lists AAC, MP3, Opus and other audio formats for Google audio receivers; codec and container must both be checked on the actual Nest device.
- [yt-dlp FAQ](https://github.com/yt-dlp/yt-dlp/wiki/FAQ) warns that extracted media URLs may need the same IP, cookies or headers, and describes relaying from the resolving machine as an alternative. A working URL on the Mac is not proof it works directly on Nest.
- [yt-dlp PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO%20Token%20Guide) documents changing attestation requirements and HTTP 403 failures. This is a maintenance dependency, not a stable public playback API.
- [PyChromecast](https://github.com/home-assistant-libs/pychromecast) provides discovery, media loading, status and controls, including audio multiroom support. It can support a small home-server spike without implementing Cast transport from scratch. It does not itself solve YouTube acquisition.
- [Music Assistant's YouTube Music provider](https://www.music-assistant.io/music-providers/youtube-music/) explicitly says free accounts are not supported and documents cookie/token setup. It is not a solution to this project's no-Premium requirement through that provider.
- [Web Video Caster](https://www.webvideocaster.com/) advertises casting website videos and phone-local media; its landing page does not demonstrate arbitrary YouTube audio to Nest with the phone offline. Do not treat it as verified for this use case.
- [YouTube terms](https://www.youtube.com/static?template=terms) restrict downloading and other reuse except as authorized by the service or with the specified permissions. [YouTube API developer policies](https://developers.google.com/youtube/terms/developer-policies) restrict separating audiovisual components. Technical feasibility is distinct from permission to ship a commercial extraction service; personal use alone is not proof of permission.

## Recommended next experiment

Use Bedroom speaker only. Keep the existing Android spike as the remote-control test surface. Compare direct receiver access with a Mac-hosted media URL; do not start with on-phone downloading.

1. Assess whether the supplied sources expose accessible audio and under what conditions. Record source failures separately from Cast failures; no assumption of successful extraction.
2. Where authorized media is available, try a compatible direct URL on Nest. Check start, seek, and later playback rather than only a short initial buffer.
3. Compare with a complete audio file served from the Mac using byte ranges. The existing generated tone can independently validate this delivery path.
4. Disconnect the phone, verify continued playback, then reconnect without reloading the media. Check current position, volume, pause/resume, stop and seek.
5. Repeat across all six sources and a full three-hour run before selecting an architecture. A short success is insufficient evidence of reliable long-form playback.

Recommendation: a Mac-hosted relay/cache is the strongest technical hypothesis for the requested experience, with direct-URL handoff worth testing as a simpler alternative. Both YouTube extraction variants remain unofficial and unvalidated here. No source videos were downloaded during this research.
