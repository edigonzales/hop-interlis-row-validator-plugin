#!/usr/bin/env python3
"""Fail on broken local assets, fragment links, downloads and missing search entries."""
from html.parser import HTMLParser
import json
from pathlib import Path
import sys
from urllib.parse import unquote, urlsplit


PAGES_BASE = '/hop-interlis-row-validator-plugin/'
HANDBOOK = 'row-validator/main/index.html'
HANDBOOK_ANCHORS = ['row-validator', '_description', '_input', '_options', '_output', '_supported_engines', '_examples', '_error_handling', '_limitations']
HPL_DOWNLOADS = 1


class Page(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.ids, self.links = set(), []
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if 'id' in values:
            self.ids.add(values['id'])
        for key in ('href', 'src'):
            if values.get(key):
                self.links.append(values[key])


def check(root):
    pages = {p.resolve(): Page(p.read_text()) for p in root.rglob('*.html')}
    assert (root / 'index.html').resolve() in pages, 'Missing start page'
    errors = []
    for path, page in pages.items():
        text = path.read_text()
        if 'Unresolved directive' in text or 'include::' in text or '&lt;&lt;' in text:
            errors.append(f'{path}: unresolved AsciiDoc reference/include')
        for link in page.links:
            url = urlsplit(link)
            if url.scheme or url.netloc:
                assert url.scheme != 'file', f'Local Git source leaked: {link}'
                continue
            local = unquote(url.path)
            if local.startswith(PAGES_BASE):
                target = root / local[len(PAGES_BASE):]
            elif local.startswith('/'):
                target = root / local.lstrip('/')
            else:
                target = path.parent / local if local else path
            if target.is_dir():
                target = target / 'index.html'
            target = target.resolve()
            if not target.exists():
                errors.append(f'{path.relative_to(root)}: missing {link}')
            elif url.fragment and target in pages and unquote(url.fragment) not in pages[target].ids:
                errors.append(f'{path.relative_to(root)}: missing anchor {link}')
    handbook = (root / HANDBOOK).resolve()
    assert handbook in pages, 'Missing single-page handbook'
    for anchor in HANDBOOK_ANCHORS:
        assert anchor in pages[handbook].ids, 'Missing section: ' + anchor
    handbook_text = handbook.read_text()
    assert 'class="listingblock gui-mockup"' in handbook_text, 'Missing gui-mockup block'
    styles = (root / 'site-assets/styles.css').read_text()
    assert '.listingblock.gui-mockup pre' in styles and 'font-size: 0.75em' in styles, \
        'Missing gui-mockup styles'
    downloads = list(root.rglob('*.hpl'))
    assert len(downloads) == HPL_DOWNLOADS, f'Expected {HPL_DOWNLOADS} pipeline downloads, got {len(downloads)}'
    search = list(root.rglob('*search*.json'))
    assert search, 'Missing search JSON'
    combined = '\n'.join(p.read_text() for p in search)
    for name in ['Row Stream Validator', 'Supported engines', 'Error handling']:
        assert name in combined, 'Search lacks ' + name
    for path in search:
        json.loads(path.read_text())
    assert not errors, '\n'.join(errors)
    print(f'Site checks passed: {len(pages)} HTML pages, {len(downloads)} downloads, {len(search)} search files')


if __name__ == '__main__':
    check(Path(sys.argv[1]).resolve())
