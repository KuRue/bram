#!/usr/bin/env python3
"""Sign exact recipe index bytes with a publisher-owned P-256 key using OpenSSL."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

DOMAIN = b"Bram recipe index v1\n"
P256_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004")


def openssl(*args):
    return subprocess.run(["openssl", *map(str, args)], stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout


def sign_index(index_path, key_path, output_path):
    if output_path.resolve() in (index_path.resolve(), key_path.resolve()):
        raise ValueError("Signature output must be separate from the index and private key")
    index = index_path.read_bytes()
    if not 0 < len(index) <= 256 * 1024:
        raise ValueError("Index must be between 1 byte and 256 KiB")
    json.loads(index.decode("utf-8"))
    public = openssl("pkey", "-in", key_path, "-pubout", "-outform", "DER")
    if len(public) != 91 or not public.startswith(P256_PREFIX):
        raise ValueError("Use a named P-256 (prime256v1) key with an uncompressed public point")
    with tempfile.TemporaryDirectory(prefix="bram-recipe-sign-") as folder:
        payload = Path(folder) / "payload"
        signature_path = Path(folder) / "signature"
        payload.write_bytes(DOMAIN + index)
        openssl("dgst", "-sha256", "-sign", key_path, "-out", signature_path, payload)
        signature = signature_path.read_bytes()
    proof = {"version": 1, "algorithm": "SHA256withECDSA",
             "publicKey": base64.b64encode(public).decode("ascii"),
             "signature": base64.b64encode(signature).decode("ascii")}
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output_path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write((json.dumps(proof, indent=2) + "\n").encode("utf-8"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output_path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return hashlib.sha256(public).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True, help="Publisher-owned PEM private key; never publish it")
    parser.add_argument("--output", type=Path, help="Defaults to index.signature.json beside the index")
    args = parser.parse_args()
    try:
        fingerprint = sign_index(args.index, args.key, args.output or args.index.with_name("index.signature.json"))
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"Signing failed ({type(failure).__name__}); check the index, key and OpenSSL installation\n")
    print(f"Signing key SHA-256: {fingerprint}")


if __name__ == "__main__":
    main()
