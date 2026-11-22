# Apps independientes

ExtYomi sigue siendo un fork de Nekoyomi. La novedad es el apartado **Explorar → Apps independientes**,
junto a Fuentes, Extensiones y Migrar. Lista las extensiones instaladas (anime y manga; novelas se
añadirán después) y permite convertir una de ellas en una app aparte.

## Qué genera el botón «Crear app»

El generador toma el APK instalado de ExtYomi —es decir, el fork completo— y produce una copia:

- nuevo `packageName` derivado del paquete de la extensión y del `sourceId`;
- nombre y icono de la extensión;
- `assets/standalone_config.json` con `sourceId`, `kind`, paquete de la extensión, versión y huella
  de su certificado;
- provider authorities reescritos para que la app generada pueda convivir con el generador;
- firma con una clave propia creada en el Android Keystore del dispositivo;
- salida únicamente en la carpeta SAF elegida por el usuario.

No se crea un módulo launcher ni un puente `OPEN_EXTENSION`. La app resultante es el fork, con la
navegación limitada por la configuración embebida.

## Cómo se comporta la app generada

Al arrancar, `StandaloneScope.initializeFromAssets()` lee la configuración y:

- marca la extensión como de confianza en el almacenamiento privado de la nueva app;
- fija el `sourceId` y el `kind` (anime o manga);
- Home muestra solo **Feed** (catálogo de esa fuente), **Biblioteca** y **Ajustes**;
- Biblioteca e Historial se filtran por ese `sourceId`;
- Ajustes muestra el reproductor para anime y el lector para manga;
- el apartado «Apps independientes» desaparece.

La app generada usa el sistema de extensiones de Nekoyomi tal cual, pero queda reducida a una sola
fuente. La extensión debe seguir instalada en el dispositivo.

## Seguridad y límites

- El APK se escribe primero en `cacheDir` privado y solo se copia al árbol SAF seleccionado.
- No se redistribuyen APKs de terceros: solo se reempaqueta el propio fork.
- La instalación queda en manos del usuario (el botón descarga/genera, no instala).
- v1 cubre anime y manga; novelas vendrán después.
- El recorte de código DEX/Retail no se hace en v1: el aislamiento es de navegación y datos.
