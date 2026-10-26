import json
import unittest
from unittest.mock import Mock

from video_metadata import fetch_video_metadata, parse_duration


class VideoMetadataTests(unittest.TestCase):
    def response(self, *, payload=None, html=b"", status=200):
        response = Mock()
        response.raise_for_status.return_value = None if status == 200 else RuntimeError(str(status))
        response.json.return_value = payload or {}
        response.iter_content.return_value = [html]
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        return response

    def http(self, *, html):
        http = Mock()
        oembed = self.response(payload={
            "title": "'VHS Dreams' | Nostalgic 80's Synth & Electro Music",
            "author_name": "Silent Depths",
            "author_url": "https://www.youtube.com/@Silent-Depths-b9j",
            "thumbnail_url": "https://i.ytimg.com/vi/WlPiQGR18jE/maxresdefault.jpg",
        })
        watch = self.response(html=html)
        http.get.side_effect = [oembed, watch]
        return http

    def test_vhs_dreams_metadata_without_media_extraction(self):
        http = self.http(html=(
            b'<html><head><meta itemprop="duration" content="PT182M54S">'
            b'<meta itemprop="datePublished" content="2026-03-20T05:12:46-07:00">'
            b'<meta itemprop="uploadDate" content="2026-03-20T05:12:46-07:00">'
            b'<meta property="og:image" content="https://i.ytimg.com/vi/WlPiQGR18jE/maxresdefault.jpg">'
            b'</head><body>ignored</body></html>'
        ))
        result = fetch_video_metadata("https://www.youtube.com/watch?v=WlPiQGR18jE", http=http)
        self.assertEqual(result["id"], "WlPiQGR18jE")
        self.assertEqual(result["title"], "'VHS Dreams' | Nostalgic 80's Synth & Electro Music")
        self.assertEqual(result["creator"], "Silent Depths")
        self.assertEqual(result["channel_url"], "https://www.youtube.com/@Silent-Depths-b9j")
        self.assertIsNone(result["channel_id"])
        self.assertEqual(result["duration"], 10974)
        self.assertEqual(result["source_published_at"], 1774008766)
        self.assertNotIn("formats", result)
        self.assertNotIn("url", result)
        self.assertEqual(http.get.call_count, 2)
        self.assertEqual(http.get.call_args_list[0].args[0], "https://www.youtube.com/oembed")
        self.assertEqual(http.get.call_args_list[1].args[0], "https://www.youtube.com/watch?v=WlPiQGR18jE")

    def test_missing_duration_stays_unknown(self):
        http = self.http(html=b"<html><head><meta property=\"og:title\" content=\"Only title\"></head></html>")
        result = fetch_video_metadata("WlPiQGR18jE", http=http)
        self.assertIsNone(result["duration"])
        self.assertIsNone(result["source_published_at"])

    def test_malformed_html_does_not_create_duration(self):
        http = self.http(html=b"<meta itemprop='duration' content='PT1H'")
        result = fetch_video_metadata("WlPiQGR18jE", http=http)
        self.assertIsNone(result["duration"])

    def test_duration_parser(self):
        self.assertEqual(parse_duration("PT3H2M54S"), 10974)
        self.assertEqual(parse_duration("PT45S"), 45)
        self.assertEqual(parse_duration("P1DT2H"), 93600)
        self.assertIsNone(parse_duration("not-a-duration"))
        self.assertIsNone(parse_duration(None))

    def test_invalid_artwork_is_rejected(self):
        http = self.http(html=b'<meta property="og:image" content="https://evil.test/signed?token=secret">')
        with self.assertRaisesRegex(ValueError, "artwork"):
            fetch_video_metadata("WlPiQGR18jE", http=http)

    def test_oembed_and_watch_page_are_the_only_network_requests(self):
        http = self.http(html=b"")
        fetch_video_metadata("WlPiQGR18jE", http=http)
        for call in http.get.call_args_list:
            self.assertNotIn("googlevideo", str(call))
            self.assertNotIn("signature", str(call))
            self.assertNotIn("yt_dlp", str(call))


if __name__ == "__main__":
    unittest.main()
