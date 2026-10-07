"""Offline regression tests for E2E downloads, deadlines and preserved diagnostics."""
import hashlib
import http.server
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

spec = importlib.util.spec_from_file_location('run_e2e', Path(__file__).with_name('run-e2e.py'))
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)
PAYLOAD = b'test distribution bytes'
DIGEST = hashlib.sha512(PAYLOAD).hexdigest().encode()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path.startswith('/missing'):
            self.send_error(404)
            return
        if self.path == '/slow':
            self.send_response(200)
            self.send_header('Content-Length', '1000')
            self.end_headers()
            try:
                for _ in range(100):
                    self.wfile.write(b'x')
                    self.wfile.flush()
                    time.sleep(0.02)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        content = DIGEST if self.path.endswith('.sha512') else PAYLOAD
        if self.path.startswith('/corrupt') and not self.path.endswith('.sha512'):
            content = b'wrong bytes'
        self.send_response(200)
        self.send_header('Content-Length', str(len(content) + (100 if self.path == '/truncated' else 0)))
        self.end_headers()
        self.wfile.write(content)


class RunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_successful_download_replaces_destination_without_partial_file(self):
        target = self.root / 'download'
        e2e.download(self.base + '/ok', target)
        self.assertEqual(PAYLOAD, target.read_bytes())
        self.assertFalse(target.with_name('download.part').exists())

    def test_failed_and_truncated_downloads_preserve_previous_destination(self):
        for route in ('missing', 'truncated'):
            with self.subTest(route=route):
                target = self.root / route
                target.write_bytes(b'previous')
                with self.assertRaises(RuntimeError):
                    e2e.download(self.base + '/' + route, target)
                self.assertEqual(b'previous', target.read_bytes())
                self.assertFalse(target.with_name(route + '.part').exists())

    def test_total_deadline_stops_a_continuously_dribbling_response(self):
        start = time.monotonic()
        with self.assertRaises(RuntimeError):
            e2e.download(self.base + '/slow', self.root / 'slow', timeout=0.2)
        self.assertLess(time.monotonic() - start, 3)
        self.assertFalse((self.root / 'slow').exists())
        self.assertFalse((self.root / 'slow.part').exists())

    def test_missing_or_corrupt_primary_falls_back_and_cached_bytes_are_rechecked(self):
        for route in ('missing', 'corrupt'):
            with self.subTest(route=route):
                cache = self.root / route
                archive = e2e.distribution(cache, (self.base + '/' + route, self.base + '/good'))
                self.assertEqual(PAYLOAD, archive.read_bytes())
                # With no sources, only a checksum-verified cache can succeed.
                self.assertEqual(archive, e2e.distribution(cache, ()))
                archive.write_bytes(b'corrupt cached bytes')
                with self.assertRaises(RuntimeError):
                    e2e.distribution(cache, ())

    def test_no_source_can_bypass_checksum_validation(self):
        with self.assertRaisesRegex(RuntimeError, 'checksum mismatch'):
            e2e.distribution(self.root, (self.base + '/corrupt',))
        self.assertFalse(list(self.root.glob('*.zip')))

    def test_process_deadline_retains_output_and_emits_heartbeat(self):
        logfile = self.root / 'hung.log'
        heartbeats = []
        with self.assertRaises(TimeoutError):
            e2e.run_logged([sys.executable, '-u', '-c',
                            'import time; print("before wait"); time.sleep(30)'],
                           logfile, timeout=0.3, progress_interval=0.1,
                           progress=heartbeats.append)
        self.assertIn('before wait', logfile.read_text())
        self.assertTrue(heartbeats)

    def test_deadline_also_stops_posix_descendants(self):
        # The Hop runner uses bash and /usr/bin/time on the supported E2E hosts.
        if os.name != 'posix':
            self.fail('Installed-Hop process-tree checks require a POSIX host')
        marker = self.root / 'child-survived'
        child = f'import time; from pathlib import Path; time.sleep(1); Path({str(marker)!r}).touch()'
        parent = (
            f'import subprocess, sys, time; subprocess.Popen([sys.executable, "-c", {child!r}]); '
            'print("child started", flush=True); time.sleep(30)'
        )
        with self.assertRaises(TimeoutError):
            e2e.run_logged([sys.executable, '-c', parent], self.root / 'parent.log', timeout=0.3)
        time.sleep(1)
        self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
