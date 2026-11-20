# Running Flux TV's tests on your Unraid server

Flux's emulator tests normally run on GitHub's computers, which start from nothing every time. On your own server
the build cache and the Android emulator files are kept, so runs are much quicker. Nothing else changes: pushes to
the `device-test` branches still start the tests, and results still land in the `device-test-output` branch.

Only pushes to the test branches and manual "Run workflow" clicks run on your server. Pull requests from other
people never do.

## 1. Turn on virtualization (once)

Unraid → **Settings → VM Manager → Enable VMs: Yes** → Apply. This makes `/dev/kvm` available, which the Android
emulator needs. (Your CPU's virtualization — Intel VT-x or AMD-V — must be on in the BIOS; it usually is.)

## 2. Get a runner code from GitHub (once)

On GitHub: **Fluxtv repo → Settings → Actions → Runners → New self-hosted runner** (choose Linux). In the
"Configure" box there's a line like `./config.sh --url https://github.com/ProfoundBandit86/Fluxtv --token ABC123…`.
Copy just the token (the part after `--token`). It's only valid for an hour, so do step 3 straight after.

## 3. Add the container in Unraid

**Docker → Add Container**, then fill in:

| Field | Value |
|---|---|
| Name | `flux-runner` |
| Repository | `ghcr.io/profoundbandit86/flux-runner:latest` |
| Network Type | `Bridge` (not Host: the tests run their own little servers, which would clash with Unraid's) |
| Extra Parameters | `--device=/dev/kvm --shm-size=2g` |
| Path: container `/data` → host | `/mnt/user/appdata/flux-runner` |
| Variable `RUNNER_TOKEN` | the token from step 2 |
| Variable `RUNNER_NAME` | `unraid` |

(Use "Add another Path, Port, Variable…" for the path and the two variables.) Click **Apply**.

The first start downloads the Android tools and emulator (about 5 GB, 10–20 minutes). Watch the container's log;
it's ready when it says **"Runner ready. Waiting for test runs."** On GitHub the runner then shows as "Idle" under
Settings → Actions → Runners. Restarts don't need a new token.

## 4. Switch the tests over

GitHub: **Fluxtv repo → Settings → Secrets and variables → Actions → Variables → New repository variable**:
name `FLUX_RUNNER`, value `unraid`.

To go back to GitHub's computers (for example while the server is off), delete the variable or change its value.
If the server is off while the variable says `unraid`, test runs wait in the queue until it's back.

## If the image won't download

If Unraid says it can't pull `ghcr.io/profoundbandit86/flux-runner`, the image may be private: on GitHub open your
profile → **Packages → flux-runner → Package settings → Change visibility → Public**.

## Memory

A test run uses about 6–8 GB of memory and 4 CPU cores while it runs, and almost nothing while idle.
