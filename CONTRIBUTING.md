# Contributing to Astra

Thank you for your interest in Astra. The project is early: the most useful contributions are design challenges and real use cases, as described in the [README](README.md#contributing), as well as fixes to the running thin slice.

## Clean-room rule

Astra is inspired by Frappe Framework and ERPNext but shares no code with them. **Do not copy code, schema, fixtures or UI assets from ERPNext (GPL-3.0) or any other GPL project.** Frappe concepts may be referenced; implementations must be original.

## Development setup

Requirements: JDK 25, Docker, and for the Kubernetes demo `kubectl` and `k3d`. The Maven wrapper is included.

```bash
./mvnw -pl astra-server -am test     # build and run the tests (PostgreSQL via Dev Services)
make infra-up && make dev            # local dev with live reload
```

See [Run the thin slice today](README.md#run-the-thin-slice-today) for the full demo stack.

## Pull requests

1. Open an issue first for anything larger than a small fix, so the design can be discussed.
2. Keep each pull request focused on one change, and add or update tests for the behaviour you touch.
3. Make sure `./mvnw -pl astra-server -am test` passes.
4. Write commit messages in the [Conventional Commits](https://www.conventionalcommits.org/) format, for example `fix(doctype): reject Links across tenants`.
5. Business apps talk to the platform only through `astra-spi`; do not depend on kernel internals from `astra-apps/*`.

By contributing you agree that your work is released under the [MIT License](LICENSE).

## Security

Do not report vulnerabilities in public issues. See [SECURITY.md](SECURITY.md).

## Code of conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md).
