"use strict";

// Privileged half of the Peel certificate store.
//
// Built-in extensions installed from resource://android/assets/ land in
// XPIProvider's BuiltInLocation, which makes them privileged; privileged
// extensions may declare experiment_apis, and experiment API parent modules
// are evaluated in a system-principal sandbox. That is what lets this file
// reach nsIX509CertDB, the same interface the desktop Certificates policy
// uses to install enterprise CAs.
//
// Certificates live in cert9.db inside the Gecko profile, so they survive
// restarts. That cuts both ways: removing an entry in Peel has to actively
// delete the cert, otherwise trust would linger forever. We therefore
// reconcile rather than append, and track what we installed in a pref so we
// never touch a certificate that came from somewhere else.

/* global ExtensionAPI, Services, Cc, Ci */

const INSTALLED_PREF = "extensions.peel.certstore.installed";

// SSL trust position only: 'C' is CERTDB_TRUSTED_CA, which is what server
// certificate validation consults. The email and object-signing positions are
// deliberately left empty -- Peel has no such surface, so granting them would
// widen the CA's authority for no benefit.
const TRUST_SSL_CA = "C,,";

const PEM_BODY = /-----BEGIN CERTIFICATE-----([\s\S]*?)-----END CERTIFICATE-----/g;

function certDB() {
  return Cc["@mozilla.org/security/x509certdb;1"].getService(Ci.nsIX509CertDB);
}

// A single list entry may hold a whole chain; treat each PEM block as its own
// certificate. Bare base64 (no armour) is accepted too, since that is what a
// user pasting from a config file often ends up with.
function extractBase64Blocks(text) {
  const blocks = [];
  const normalized = String(text || "").trim();
  if (!normalized) {
    return blocks;
  }

  PEM_BODY.lastIndex = 0;
  let match = PEM_BODY.exec(normalized);
  while (match !== null) {
    const body = match[1].replace(/\s+/g, "");
    if (body) {
      blocks.push(body);
    }
    match = PEM_BODY.exec(normalized);
  }

  if (!blocks.length && !normalized.includes("-----")) {
    const body = normalized.replace(/\s+/g, "");
    if (body) {
      blocks.push(body);
    }
  }

  return blocks;
}

function readInstalledFingerprints() {
  try {
    const raw = Services.prefs.getStringPref(INSTALLED_PREF, "[]");
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter(fp => typeof fp === "string") : [];
  } catch (e) {
    return [];
  }
}

function writeInstalledFingerprints(fingerprints) {
  try {
    Services.prefs.setStringPref(INSTALLED_PREF, JSON.stringify(fingerprints));
  } catch (e) {
    Services.console.logStringMessage(
      "peelCerts: unable to persist installed fingerprints: " + e
    );
  }
}

function normalizeFingerprint(fp) {
  return String(fp || "").replace(/:/g, "").toUpperCase();
}

function describe(cert) {
  let notAfter = 0;
  try {
    // PRTime is microseconds since the epoch.
    notAfter = Math.floor(cert.validity.notAfter / 1000);
  } catch (e) {
    notAfter = 0;
  }
  return {
    fingerprint: normalizeFingerprint(cert.sha256Fingerprint),
    commonName: cert.commonName || cert.subjectName || "",
    issuer: cert.issuerName || "",
    notAfter,
    isCA: !!(cert.certType & Ci.nsIX509Cert.CA_CERT),
  };
}

// Fingerprint -> cert, over everything currently in the profile's store.
// A failure here has to propagate: an empty map would read as "nothing is
// installed", which would drop still-trusted certificates from the tracking
// pref and leave them with nothing able to remove them.
function currentCertsByFingerprint() {
  const map = new Map();
  for (const cert of certDB().getCerts()) {
    try {
      map.set(normalizeFingerprint(cert.sha256Fingerprint), cert);
    } catch (e) {
      // A cert we cannot fingerprint is one we can never match; skip it.
    }
  }
  return map;
}

this.peelCerts = class extends ExtensionAPI {
  getAPI() {
    return {
      peelCerts: {
        async sync(pems) {
          const db = certDB();
          const desired = new Map();
          const errors = [];

          // Parse and fingerprint everything up front. constructX509FromBase64
          // validates the encoding without touching the trust store, so a
          // malformed entry is reported and skipped rather than aborting the
          // whole sync.
          for (const pem of pems || []) {
            const blocks = extractBase64Blocks(pem);
            if (!blocks.length) {
              errors.push({ reason: "unparsable" });
              continue;
            }
            for (const base64 of blocks) {
              let cert;
              try {
                cert = db.constructX509FromBase64(base64);
              } catch (e) {
                errors.push({ reason: "invalid", message: String(e) });
                continue;
              }
              const info = describe(cert);
              if (!info.isCA) {
                errors.push({ reason: "not-a-ca", commonName: info.commonName });
                continue;
              }
              desired.set(info.fingerprint, { base64, info });
            }
          }

          const previouslyInstalled = new Set(
            readInstalledFingerprints().map(normalizeFingerprint)
          );
          const present = currentCertsByFingerprint();
          const installed = [];
          const tracked = [];

          for (const [fingerprint, entry] of desired) {
            const existing = present.get(fingerprint);
            let trusted = false;
            if (existing) {
              try {
                trusted = db.isCertTrusted(
                  existing,
                  Ci.nsIX509Cert.CA_CERT,
                  Ci.nsIX509CertDB.TRUSTED_SSL
                );
              } catch (e) {
                trusted = false;
              }
            }

            if (!trusted) {
              try {
                db.addCertFromBase64(entry.base64, TRUST_SSL_CA);
              } catch (e) {
                errors.push({
                  reason: "install-failed",
                  commonName: entry.info.commonName,
                  message: String(e),
                });
                continue;
              }
            }

            // Ownership, not mere presence, decides what we may later delete.
            // A CA that was already trusted before we touched it came from
            // somewhere else, so removing it from the list must not delete it.
            // Reporting it as installed keeps the ack honest either way.
            if (previouslyInstalled.has(fingerprint) || !trusted) {
              tracked.push(fingerprint);
            }
            installed.push(entry.info);
          }

          // Anything we installed on a previous run that is no longer wanted
          // has to go, or the user's removal would be cosmetic only.
          const undeleted = [];
          for (const fingerprint of previouslyInstalled) {
            if (desired.has(fingerprint)) {
              continue;
            }
            const stale = present.get(fingerprint);
            if (!stale) {
              continue;
            }
            try {
              db.deleteCertificate(stale);
            } catch (e) {
              // Keep tracking it so the next sync retries; forgetting it here
              // would leave the certificate trusted with nothing to remove it.
              undeleted.push(fingerprint);
              errors.push({
                reason: "delete-failed",
                fingerprint,
                message: String(e),
              });
            }
          }

          writeInstalledFingerprints(tracked.concat(undeleted));

          return { installed, errors };
        },
      },
    };
  }
};
