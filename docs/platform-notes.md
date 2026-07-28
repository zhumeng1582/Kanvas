# Platform notes

Kanvas targets Android and Jetpack Compose. The following boundaries are
intentional and form part of the public platform contract.

## Rendering

- Canvas dimensions are resolved from logical units at the Compose boundary.
- The Android magnifier controls size, zoom, margins, clipping, and border
  projection; platform support determines the final system rendering.
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
