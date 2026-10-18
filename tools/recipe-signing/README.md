# Signing recipe indexes

Each network source must publish `index.signature.json` next to `index.json`. The index and recipe
schemas stay unchanged. Sign the final index bytes after updating the recipe hashes; changing any
index byte (including whitespace) invalidates the signature.

The app verifies P-256 ECDSA with SHA-256 using Android's platform `SHA256withECDSA` implementation
([supported since API 11](https://developer.android.com/reference/java/security/Signature)). The
signed payload is the UTF-8 bytes `Bram recipe index v1\n` followed immediately by the exact index
bytes, with no JSON canonicalization. The context string prevents a signature made for another
protocol from being accepted here. The signed index binds its source identity and all recipe hashes.
It is portable across URLs; trusting a key at another URL still requires a new source review.

The detached JSON has exactly four fields: integer `version: 1`, `algorithm: "SHA256withECDSA"`,
`publicKey`, and `signature`. Both byte fields use canonical padded Base64. The public key is the
91-byte DER SubjectPublicKeyInfo for named `prime256v1`/`secp256r1` with an uncompressed point.
The signature uses ASN.1 DER encoding, not the concatenated IEEE P1363 format. Proofs are capped
at 4 KiB. The key fingerprint is lowercase SHA-256 of those public-key DER bytes.

Generate and retain your publisher key outside the repository and web root. The following uses
OpenSSL; protect the private key with your normal publisher-secret storage and backups.

```bash
umask 077
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1 -out /secure/path/recipes.pem
python3 tools/recipe-signing/sign_index.py --index /publish/recipes/index.json --key /secure/path/recipes.pem
```

Publish the index, detached signature and recipe documents together. Share the printed key
fingerprint over a separately trusted channel so people can check it during first-use review.
The helper takes an existing PEM key, calls OpenSSL without a shell, writes only the signature
sidecar, and never copies private material into a publication directory. It does not prompt for
passphrases; use your publisher's noninteractive secret workflow if the key is encrypted.

Every snapshot update needs manual review. A changed key, including on identical index bytes,
needs separate explicit approval of the new fingerprint. There is no automatic trust transfer
from a new self-signature, no remote grant of bundled status, and no unsigned download fallback.
An accepted rotation retires the old key for future updates. Offline rollback is allowed only
between snapshots using the current pinned key. To return to another key, fetch and explicitly
review that key again. This protocol does not provide an external certificate identity, an
automatic freshness/expiry service, or global revocation; the UI names exactly the key being trusted.

The public `app/src/androidTest/assets/recipe-signing/` fixture was signed by this helper with a disposable key that was discarded.
It is a compatibility vector, not a publisher key to trust. Unit and device tests also generate
ephemeral keys to exercise acceptance, corruption, rotation and cache persistence.
