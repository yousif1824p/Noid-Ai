# Controlled local HTTP assessment lab

This standard-library-only host test starts an intentionally vulnerable HTTP
fixture on **127.0.0.1** and performs two bounded dynamic checks:

- An external redirect probe. The checker inspects the Location response and
  never follows it.
- A path-traversal probe against a temporary canary file outside the fixture
  root. The fixture is deliberately vulnerable and exists only inside the test.

The checker rejects non-loopback hosts, alternate schemes, credentials, paths,
queries, and origins without an explicit port before opening a connection.
It sends requests only to two fixed paths and reads at most 4 KiB per response.

Run:

    cd tools/controlled-http-lab
    python -m unittest discover -s tests -v

**Limitations:** this validates the host-side HTTP probe and fixture, not an
Android APK, Android process, real application, remote service, or device
network stack. It does not authorize scanning any external target. The
production Android assessment remains read-only and simulation-only for
dynamic checks until a separately authorized Android test application and
device test workflow exist.
