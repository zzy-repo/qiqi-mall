#!/usr/bin/env python3
"""certbot manual-plugin DNS-01 hook backed by the Aliyun DNS (alidns) API.

Usage: dns_hook.py present|cleanup
Environment provided by certbot: CERTBOT_DOMAIN, CERTBOT_VALIDATION.
Credentials are loaded by ali.py and are never printed.
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ali  # noqa: E402

from aliyunsdkalidns.request.v20150109 import (  # noqa: E402
    AddDomainRecordRequest,
    DeleteDomainRecordRequest,
)

ZONES = ["rose7.cn", "roselyn.cn"]
STATE = os.path.join(HERE, "dns_hook_state.json")
RESOLVER = "223.5.5.5"


def zone_of(domain):
    for z in ZONES:
        if domain == z or domain.endswith("." + z):
            return z
    raise SystemExit(f"no hosted zone known for {domain}")


def rr_of(domain, zone):
    return "_acme-challenge" if domain == zone else "_acme-challenge." + domain[: -(len(zone) + 1)]


def load_state():
    if os.path.exists(STATE):
        with open(STATE, "r", encoding="utf-8") as fh:
            return json.load(fh)
    return []


def save_state(items):
    with open(STATE, "w", encoding="utf-8") as fh:
        json.dump(items, fh, indent=2)


def present():
    domain = os.environ["CERTBOT_DOMAIN"]
    value = os.environ["CERTBOT_VALIDATION"]
    zone = zone_of(domain)
    rr = rr_of(domain, zone)
    fqdn = f"{rr}.{zone}"

    client = ali.dns_client()
    req = AddDomainRecordRequest.AddDomainRecordRequest()
    req.set_DomainName(zone)
    req.set_RR(rr)
    req.set_Type("TXT")
    req.set_Value(value)
    req.set_TTL(600)
    resp = ali.call(client, req, quiet=True)
    record_id = str(resp.get("RecordId"))
    print(f"[hook] TXT added {fqdn} id={record_id}")

    items = load_state()
    items.append({"fqdn": fqdn, "record_id": record_id, "zone": zone, "rr": rr})
    save_state(items)

    wait_propagation(fqdn, value)


def authoritative_ns(zone):
    out = subprocess.run(
        ["dig", "+short", "NS", zone, "@" + RESOLVER],
        capture_output=True, text=True,
    ).stdout
    return [line.strip().rstrip(".") for line in out.splitlines() if line.strip()]


def _txt_on(fqdn, server, value):
    out = subprocess.run(
        ["dig", "+short", "TXT", fqdn, "@" + server],
        capture_output=True, text=True,
    ).stdout
    return value in out


def wait_propagation(fqdn, value, timeout=240):
    """The authoritative zone is what Let's Encrypt queries, so trust it first.

    A public recursive resolver such as 223.5.5.5 may keep serving a cached
    negative/nonexistent answer for up to its TTL, which would otherwise stall
    the hook for minutes on re-validation of the same name.
    """
    zone = ".".join(fqdn.split(".")[-2:])
    ns_list = authoritative_ns(zone) or [RESOLVER]
    start = time.time()
    while time.time() - start < timeout:
        for server in ns_list:
            if _txt_on(fqdn, server, value):
                print(f"[hook] {fqdn} visible on authoritative @{server} after {int(time.time() - start)}s")
                return
        time.sleep(3)
    # last resort: public resolver
    if _txt_on(fqdn, RESOLVER, value):
        print(f"[hook] {fqdn} visible on {RESOLVER} after {int(time.time() - start)}s")
        return
    print(f"[hook] WARN: {fqdn} not confirmed within {timeout}s (continuing; LE validates independently)")


def cleanup():
    items = load_state()
    client = ali.dns_client()
    remaining = []
    for item in items:
        req = DeleteDomainRecordRequest.DeleteDomainRecordRequest()
        req.set_RecordId(item["record_id"])
        try:
            ali.call(client, req, quiet=True)
            print(f"[hook] TXT deleted {item['fqdn']} id={item['record_id']}")
        except Exception as exc:  # already gone
            print(f"[hook] TXT delete skipped {item['fqdn']}: {ali.redact(exc)}")
            remaining.append(item)
    save_state(remaining)


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else ""
    {"present": present, "cleanup": cleanup}.get(action, lambda: sys.exit("bad action"))()
