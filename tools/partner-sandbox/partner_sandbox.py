"""Sandbox of the demo's outside partners: FastShip (a last-mile carrier) and two tax authorities' e-invoice
services (Vietnam, Cambodia). Partners give integrators a sandbox like this one; it answers with realistic codes
after 20-80 ms. Standard library only. Run: python3 partner_sandbox.py [port]"""
import itertools
import json
import random
import sys
import threading
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

counters = {"fastship": itertools.count(1), "vn": itertools.count(1), "kh": itertools.count(1)}
lock = threading.Lock()
FEES = {"Same city": 16000, "Domestic": 26000, "Remote": 38000}


def next_no(key):
    with lock:
        return next(counters[key])


def fastship(body):
    zone = body.get("zone", "Domestic")
    service = body.get("service", "STD")
    hours = 2 if service == "FAST-2H" else {"Same city": 8, "Domestic": 36, "Remote": 72}.get(zone, 48)
    fee = FEES.get(zone, 26000) + int(max(0, float(body.get("weight_kg", 1)) - 1) * 4000) + (6000 if service == "FAST-2H" else 0)
    return {"tracking": "FS%s%05d" % (datetime.now().strftime("%y%m%d"), next_no("fastship")), "fee": fee,
            "service": service, "eta": (datetime.now() + timedelta(hours=hours)).strftime("%Y-%m-%dT%H:%M")}


def tax_vn(body):
    n = next_no("vn")
    return {"number": "%s-%08d" % (body.get("symbol", "1C26TAS"), n), "lookup": "VN-%06X" % random.randrange(16 ** 6),
            "status": "issued", "authority": "Tổng cục Thuế (sandbox)"}


def tax_kh(body):
    n = next_no("kh")
    return {"number": "KH-INV-%s-%06d" % (datetime.now().year, n), "lookup": "KH-%06X" % random.randrange(16 ** 6),
            "status": "issued", "authority": "General Department of Taxation (sandbox)"}


ROUTES = {"/fastship/bookings": fastship, "/tax/vn/invoices": tax_vn, "/tax/kh/invoices": tax_kh}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        route = ROUTES.get(self.path.split("?")[0])
        if route is None:
            return self.reply(404, {"error": "no such endpoint: " + self.path})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except ValueError:
            return self.reply(400, {"error": "body is not JSON"})
        time.sleep(random.uniform(0.02, 0.08))
        self.reply(200, route(body))

    def do_GET(self):
        self.reply(200, {"status": "ok"}) if self.path == "/health" else self.reply(404, {"error": "not found"})

    def reply(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s %s\n" % (self.headers.get("X-Astra-Plugin", "-"), self.command, self.path))
        sys.stdout.flush()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
    print("partner sandbox on :%d" % port, flush=True)
    ThreadingHTTPServer(("", port), Handler).serve_forever()
