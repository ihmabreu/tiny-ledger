# API versioning

Every path is prefixed `/api/v1`, and the OpenAPI document carries `info.version: 1.0.0`.

## Why a version from day one

Adding a version prefix later is not a small change: it breaks every client at the exact moment
you are trying to ship something else. Starting with `/api/v1` costs six characters now and buys
the ability to introduce `/api/v2` alongside it, run both while consumers migrate, and retire v1
on a published date rather than by surprise.

The version is in the URL rather than in a header or a media type. Header-based versioning is
arguably cleaner, but a URL version is visible in logs, curl commands, browser bars and
dashboards — and a version you can see is a version people reason about correctly.

## What counts as a breaking change

**Breaking** — requires `/api/v2`:

- removing or renaming a field, an endpoint or an enum value
- changing a field's type, or making an optional request field required
- narrowing accepted input, or changing a status code for an existing outcome
- changing the meaning of an existing field, even if its type is unchanged

That last one is the dangerous case, because nothing about it looks like a breaking change in a
diff. If `availableBalance` ever started including the overdraft allowance, every client would
keep parsing it successfully and every one of them would be wrong. Which is precisely why
`availableBalance` and `accountBalance` are two separate fields rather than one field with a
flag.

**Non-breaking** — ships within v1 as a minor or patch bump of `info.version`:

- adding an optional request field, or a new response field
- adding a new endpoint
- adding a new error `code` value for a *new* failure condition
- rewording an error `message` (the `code` is the stable contract; the `message` is for humans)

## How the contract is kept honest

The contract is hand-written and served verbatim: annotation scanning is disabled
(`mp.openapi.scan.disable=true`), so `/q/openapi` returns the reviewed, committed file rather
than a description generated from whatever the code currently does.

That inverts the usual relationship. A generated spec documents the implementation, so the
implementation can never be wrong — it can only be surprising. A hand-written spec states the
promise, and the integration tier then validates every request and response against it, failing
the build when the code drifts. An accidental field rename cannot reach a client, because it
cannot get past CI.

`ApiDocumentTest` additionally asserts that the served document matches the committed file and
declares the expected version, so the spec cannot be quietly regenerated back into a
description-of-the-code.

## If there were a v2

1. Add `openapi-v2.yaml`; keep v1's file untouched.
2. Add `api/v2/` resources. The service and domain layers are unversioned and shared — a version
   is a presentation concern, and duplicating the domain per version is how two divergent ledgers
   get created by accident.
3. Run both. Announce a v1 sunset date and a deprecation window; add `Deprecation` and `Sunset`
   headers to v1 responses.
4. Keep the v1 contract tests running unchanged for the whole window. The value of a contract is
   that it is still enforced after everyone has moved on to the new one.
