# Platform notes

Kanvas targets Android and Jetpack Compose. The following boundaries are
intentional and form part of the public platform contract.

## Rendering

- Canvas dimensions are resolved from logical units at the Compose boundary.
- Drawing owns magnifier configuration; `kanvas-compose` applies Android
  `Modifier.magnifier` at the chart boundary. Platform support determines final
  system rendering.
- Indicator calculations can use `BigDecimal` with `DECIMAL128`, while final
  Canvas samples are represented as `Double`.

## Persistence

- Drawing overlays store timestamps and values rather than screen positions.
- Storage ownership remains with the host application so it can choose
  DataStore, Room, files, or another Android persistence solution.

## Publishing

- Maven publications include source and Dokka Javadoc artifacts.
- Central publishing and in-memory PGP signing are credential-driven; local
  builds do not require release secrets.
- No generally available remote coordinate is declared yet. Source and
  workspace-Maven consumption remain the supported installation paths until a
  release announcement says otherwise.
