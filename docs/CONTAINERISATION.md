# Containerisation

The image is built with **Jib**, via the `quarkus-container-image-jib` extension. There is no
Dockerfile in this repository, and that is deliberate.

## Why Jib

Jib assembles the image layers itself — it reads the build output and writes an OCI image
directly. Three consequences:

- **No Dockerfile to drift.** The image contents follow the build, not a parallel description of
  it that someone has to remember to update.
- **No daemon needed to push.** Jib can push straight to a registry, which is what CI wants.
- **Cross-architecture without emulation.** Because Jib writes layers rather than *executing*
  the image, it can produce an `arm64` image on an `amd64` host and vice versa with no QEMU and
  no measurable cost. A `docker build` targeting another architecture has to emulate, and pays
  for it.

Layers are also split sensibly for the JVM: dependencies, then the application. Changing one
class re-pushes a few kilobytes rather than the whole application layer.

## Building locally

Needs a running Docker daemon (Docker Desktop, Colima, Rancher Desktop — any of them).

```bash
./gradlew :app:build -Dquarkus.container-image.build=true
docker run --rm -p 8080:8080 teya/tiny-ledger:1.0.0
curl -s http://localhost:8080/api/v1/accounts
```

The image name comes from `application.yml`
(`quarkus.container-image.group` / `.name` / `.tag`) and produces `teya/tiny-ledger:1.0.0`, a
~413 MB JVM image on `registry.access.redhat.com/ubi9/openjdk-21-runtime`.

**Jib defaults to `linux/amd64` regardless of the host.** That is convenient on CI and
surprising on an Apple Silicon laptop, where the resulting image runs under emulation. To build
for the host architecture instead:

```bash
./gradlew :app:build -Dquarkus.container-image.build=true \
  -Dquarkus.jib.platforms=linux/arm64
```

Both were verified on an arm64 host: the `linux/arm64` image reports `aarch64` inside the
container and serves the API natively, and the `linux/amd64` image reports `x86_64` and serves
the API under emulation.

> If the build fails with **"Cannot get an executable name when no container runtime is
> available"**, the Docker daemon is not running. Jib needs it to *load* the finished image
> locally — not to build it.

## Multi-architecture (amd64 + arm64)

A local build deliberately produces a **single**, one-architecture image, because the Docker
daemon cannot hold a multi-architecture manifest list. Multi-arch is therefore produced when
pushing to a registry, where manifest lists are supported:

```bash
./gradlew :app:build \
  -Dquarkus.profile=multiarch \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=<your-registry> \
  -Dquarkus.container-image.username=<user> \
  -Dquarkus.container-image.password=<token>
```

The `multiarch` profile supplies the platform list:

```properties
%multiarch.quarkus.jib.platforms=linux/amd64,linux/arm64
```

Keeping it in a profile rather than as an unconditional setting is what makes the local build
work at all: the same property that is required for a registry push is what breaks a daemon
load. Setting it globally produces a build that fails on every developer machine and succeeds
only in CI — the worst of both.

### Verifying it without a real registry

The multi-arch path is easy to leave untested until a release, which is a poor time to discover
it is broken. A throwaway registry closes that gap, and this is exactly what the
`multiarch-manifest` CI job does:

```bash
docker run -d --rm --name tl-registry -p 5001:5000 registry:2

./gradlew :app:build -x test -x bddTest -x integrationTest -x concurrencyTest -x e2eTest \
  -Dquarkus.profile=multiarch \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=localhost:5001 \
  -Dquarkus.container-image.insecure=true
```

`quarkus.container-image.insecure=true` is what lets Jib fall back to plain HTTP; without it the
push fails TLS verification against the local registry. Then inspect the manifest list:

```bash
curl -s -H 'Accept: application/vnd.docker.distribution.manifest.list.v2+json' \
  http://localhost:5001/v2/teya/tiny-ledger/manifests/1.0.0 | python3 -m json.tool
```

which returns two entries:

```json
{
  "mediaType": "application/vnd.docker.distribution.manifest.list.v2+json",
  "manifests": [
    { "platform": { "architecture": "amd64", "os": "linux" }, "...": "..." },
    { "platform": { "architecture": "arm64", "os": "linux" }, "...": "..." }
  ]
}
```

Docker then selects the right one automatically, or you can force either:

```bash
docker run --rm --platform linux/arm64 -p 8080:8080 localhost:5001/teya/tiny-ledger:1.0.0
```

Both were pulled from this manifest list and started successfully on an arm64 host: the arm64
image reports `aarch64` and runs natively, the amd64 image reports `x86_64` and runs under
emulation.

## What CI does

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) covers the whole path:

- **`container-image`** — a matrix over `linux/amd64` and `linux/arm64`. Each leg builds the
  image, asserts the reported platform, then *starts the container* and drives a real
  open-account → deposit → read-balance sequence against it. A build that succeeds and an image
  that boots and serves are different claims; only the second is worth much. QEMU is registered
  so the runner can execute the non-native image — note that Jib needs no emulation to *build*
  for another architecture, only to *run* the result.
- **`multiarch-manifest`** — spins up a `registry:2` service container, pushes the combined
  image with the `multiarch` profile, and fails unless the manifest list contains exactly
  `linux/amd64` and `linux/arm64`.

Nothing is pushed anywhere outside the job, so no registry credentials are needed.

### Performance is not portable

Both architectures are *correct* — all test tiers pass on both, including the concurrency tier,
which matters because the two have different memory models. Performance is another matter: under
emulation the latency tail degrades substantially (p99 doubles, worst case degrades roughly
sevenfold) while median and p95 are unchanged. Run the image built for the architecture you
deploy on. Details in [TESTING.md](TESTING.md#measured-results).

## Running it

```bash
docker run --rm -p 8080:8080 \
  -e LEDGER_SEED_ENABLED=false \
  teya/tiny-ledger:1.0.0
```

Any Quarkus property can be overridden at runtime through an environment variable using the
standard mapping (`ledger.seed.enabled` → `LEDGER_SEED_ENABLED`), or with `-Dproperty=value`
passed as arguments.

Remember that **all state is in memory**: two replicas of this image do not share accounts, and
restarting a container starts an empty ledger. That is a property of the assessment's scope, not
of the packaging — see [ASSUMPTIONS.md](ASSUMPTIONS.md#consciously-out-of-scope).

## Native images

Not configured. A GraalVM native build would cut startup to milliseconds and resident memory
substantially, but it also cross-compiles poorly — a genuinely multi-architecture native build
needs a builder per architecture. Since this service has no cold-start pressure, the JVM image
is the better trade here, and the reasoning is recorded so the option is a decision rather than
an oversight.
