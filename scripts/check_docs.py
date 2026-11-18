"""Offline documentation integrity checks; no web fetches or deployment."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

ROOT = Path("docs")
BASE = "https://hamzador.github.io/DataMask4j/"

class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.headings = 0
        self.title = 0
        self.description = 0
        self.canonical = []
        self.links = []
        self.in_head = False

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        assert tag != "script", "Documentation must not execute scripts"
        if tag == "h1":
            self.headings += 1
        if tag == "head":
            self.in_head = True
        if tag == "title" and self.in_head:
            self.title += 1
        if self.in_head and tag == "meta" and attrs.get("name") == "description" and attrs.get("content"):
            self.description += 1
        if self.in_head and tag == "link" and attrs.get("rel") == "canonical":
            self.canonical.append(attrs.get("href"))
        if tag in ("a", "link"):
            href = attrs.get("href")
            if href and not urlsplit(href).scheme:
                self.links.append(urlsplit(href).path)

    def handle_endtag(self, tag):
        if tag == "head":
            self.in_head = False

pages = sorted(ROOT.glob("*.html"))
assert pages, "Missing pages"
expected = set()
for file in pages:
    parsed = Page()
    parsed.feed(file.read_text(encoding="utf-8"))
    canonical = BASE + ("" if file.name == "index.html" else file.name)
    expected.add(canonical)
    assert parsed.title == parsed.description == parsed.headings == 1, (file, parsed.title, parsed.description, parsed.headings)
    assert parsed.canonical == [canonical], file
    for href in parsed.links:
        if href:
            target = (ROOT / href).resolve()
            assert target.is_relative_to(ROOT.resolve()) and target.is_file(), (file, href)
locations = {entry.text for entry in ET.parse(ROOT / "sitemap.xml").findall(".//{*}loc")}
assert locations == expected
assert "Sitemap: " + BASE + "sitemap.xml" in (ROOT / "robots.txt").read_text()
print(f"Documentation: {len(pages)} pages validated")
