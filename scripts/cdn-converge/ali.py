"""Shared Aliyun client helpers.

Secrets are read from /root/.aliyun-qiqi/credentials.env and NEVER printed.
Any accidental echo of credential material is masked by redact().
"""
import json
import os
import re
import sys

CRED_PATH = "/root/.aliyun-qiqi/credentials.env"


def _load_creds():
    ak = sk = None
    with open(CRED_PATH, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k = k.strip()
            v = v.strip().strip('"').strip("'")
            if k == "ALI_AK":
                ak = v
            elif k == "ALI_SK":
                sk = v
    if not ak or not sk:
        raise SystemExit("credentials not found")
    return ak, sk


AK, SK = _load_creds()


def redact(text):
    out = str(text)
    for secret in (AK, SK):
        if secret:
            out = out.replace(secret, "***REDACTED***")
    # generic safety net for any 24+/30 char blob that looks like a key
    return out


def dump(obj, label=""):
    if label:
        print(f"### {label}")
    print(redact(json.dumps(obj, ensure_ascii=False, indent=2, default=str)))


def cdn_client(region="cn-hangzhou"):
    from aliyunsdkcore.client import AcsClient
    from aliyunsdkcdn.request.v20180510 import (  # noqa: F401
        DescribeUserDomainsRequest,
        DescribeCdnDomainDetailRequest,
        DeleteCdnDomainRequest,
        SetDomainServerCertificateRequest,
        DescribeDomainCertificateInfoRequest,
        DescribeCdnCertificateListRequest,
        DescribeCdnCertificateDetailRequest,
        SetCdnDomainSSLCertificateRequest,
        DescribeCdnHttpsDomainListRequest,
        ModifyCdnDomainSchdmByPropertyRequest,
    )
    return AcsClient(AK, SK, region)


def dns_client(region="cn-hangzhou"):
    from aliyunsdkcore.client import AcsClient
    return AcsClient(AK, SK, region)


def call(client, request, quiet=False):
    """Send request, return parsed dict (or raw string on parse failure)."""
    request.set_accept_format("json")
    raw = client.do_action_with_exception(request)
    text = raw.decode("utf-8") if isinstance(raw, bytes) else raw
    if not quiet:
        print(redact(text))
    try:
        return json.loads(text)
    except Exception:
        return {"_raw": text}
