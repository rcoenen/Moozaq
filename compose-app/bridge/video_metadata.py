import re
from html.parser import HTMLParser
from urllib.parse import parse_qs, urlparse

import requests

from catalog import channel_url
from catalog_sqlite import canonical_channel_id, utc_timestamp, validate_video_id


MAX_WATCH_PAGE_BYTES = 4_000_000


def youtube_url(value):
    value = value.strip()
    if re.fullmatch(r"[\w-]{11}", value):
        video_id = value
    else:
        parsed = urlparse(value)
        if parsed.scheme != "https" or parsed.hostname not in ("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com"):
            raise ValueError("Enter a YouTube HTTPS link or an 11-character video ID.")
        video_id = parsed.path.strip("/") if parsed.hostname == "youtu.be" else parse_qs(parsed.query).get("v", [None])[0]
    validate_video_id(video_id)
    return f"https://www.youtube.com/watch?v={video_id}"


class PublicMetaParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.values = {}

    def handle_starttag(self, tag, attrs):
        if tag != "meta":
            return
        attributes = dict(attrs)
        key = attributes.get("itemprop") or attributes.get("property") or attributes.get("name")
        if key in {"duration", "datePublished", "uploadDate", "channelId", "og:title", "og:image"}:
            self.values.setdefault(key, attributes.get("content"))


def parse_duration(value):
    if not value:
        return None
    match = re.fullmatch(
        r"P(?:(?P<days>\d+)D)?(?:T(?:(?P<hours>\d+)H)?(?:(?P<minutes>\d+)M)?(?:(?P<seconds>\d+)S)?)?",
        value.strip(),
    )
    if not match:
        return None
    parts = {key: int(value or 0) for key, value in match.groupdict().items()}
    return parts["days"] * 86400 + parts["hours"] * 3600 + parts["minutes"] * 60 + parts["seconds"]


def public_thumbnail(value):
    if not value:
        return None
    parsed = urlparse(value)
    if parsed.scheme != "https" or parsed.hostname not in ("i.ytimg.com", "img.youtube.com"):
        raise ValueError("YouTube returned an unsupported artwork URL.")
    return value


def read_limited_html(response):
    chunks = []
    size = 0
    for chunk in response.iter_content(65536):
        size += len(chunk)
        if size > MAX_WATCH_PAGE_BYTES:
            raise ValueError("YouTube metadata page exceeds the size limit.")
        chunks.append(chunk)
    return b"".join(chunks).decode("utf-8", errors="replace")


def fetch_video_metadata(value, http=requests):
    source = youtube_url(value)
    video_id = parse_qs(urlparse(source).query)["v"][0]
    oembed = http.get("https://www.youtube.com/oembed", params={"url": source, "format": "json"}, timeout=15)
    oembed.raise_for_status()
    data = oembed.json()
    with http.get(source, stream=True, timeout=20) as response:
        response.raise_for_status()
        parser = PublicMetaParser()
        parser.feed(read_limited_html(response))
    metadata = parser.values
    author_url = data.get("author_url")
    if author_url:
        author_url = channel_url(author_url)
    thumbnail = public_thumbnail(metadata.get("og:image") or data.get("thumbnail_url"))
    title = data.get("title") or metadata.get("og:title")
    if not isinstance(title, str) or not title.strip():
        raise ValueError("YouTube did not return a title for this video.")
    publication = metadata.get("datePublished") or metadata.get("uploadDate")
    return {
        "id": video_id,
        "title": title,
        "creator": data.get("author_name") or "",
        "channel_url": author_url,
        "channel_id": metadata.get("channelId") or (canonical_channel_id(author_url) if author_url else None),
        "thumbnail": thumbnail,
        "duration": parse_duration(metadata.get("duration")),
        "source_published_at": utc_timestamp(publication) if publication else None,
        "webpage_url": source,
    }
