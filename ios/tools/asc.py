"""Tiny App Store Connect API helper (JWT from an API key; never prints the key).

  python asc.py GET /v1/bundleIds?filter[identifier]=app.wisperkeys
  python asc.py POST /v1/bundleIds '{"data": {...}}'
Env: ASC_KEY_PATH, ASC_KEY_ID, ASC_ISSUER_ID.
"""
import json, os, sys, time, urllib.request, urllib.error
import jwt


def token():
    key = open(os.environ["ASC_KEY_PATH"]).read()
    now = int(time.time())
    return jwt.encode({"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"},
                      key, algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})


def call(method, path, body=None):
    req = urllib.request.Request("https://api.appstoreconnect.apple.com" + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


if __name__ == "__main__":
    status, out = call(sys.argv[1], sys.argv[2], json.loads(sys.argv[3]) if len(sys.argv) > 3 else None)
    print(status)
    print(json.dumps(out, indent=1)[:6000])
