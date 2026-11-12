#!/usr/bin/env python3
"""certbot --deploy-hook: push the freshly renewed certificate to Aliyun CDN.

Uploads the Let's Encrypt cert for the domains below via SetCdnDomainSSLCertificate
(certType=upload) and switches HTTPS protocol on. Idempotent; safe to re-run.
Credentials come from ali.py and are never printed.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ali  # noqa: E402

from aliyunsdkcdn.request.v20180510 import SetCdnDomainSSLCertificateRequest  # noqa: E402

LINEAGE = os.path.join(HERE, "letsencrypt", "live", "cdn.rose7.cn")
DOMAINS = ["cdn.rose7.cn", "docs.rose7.cn"]


def main():
    pub = open(os.path.join(LINEAGE, "fullchain.pem"), encoding="utf-8").read()
    pri = open(os.path.join(LINEAGE, "privkey.pem"), encoding="utf-8").read()
    client = ali.cdn_client()
    rc = 0
    for domain in DOMAINS:
        req = SetCdnDomainSSLCertificateRequest.SetCdnDomainSSLCertificateRequest()
        req.set_DomainName(domain)
        req.set_CertName(f"{domain}-le")
        req.set_CertType("upload")
        req.set_SSLProtocol("on")
        req.set_SSLPub(pub)
        req.set_SSLPri(pri)
        try:
            resp = ali.call(client, req, quiet=True)
            print(f"[deploy] {domain}: OK {ali.redact(str(resp))}")
        except Exception as exc:
            print(f"[deploy] {domain}: FAILED {ali.redact(exc)}")
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
