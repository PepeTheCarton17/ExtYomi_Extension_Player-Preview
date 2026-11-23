# Apps independientes

ExtYomi sigue siendo un fork de Nekoyomi. La novedad es el apartado **Explorar → Apps independientes**,
junto a Fuentes, Extensiones y Migrar. Lista las extensiones instaladas agrupadas por tipo
(**Anime / Manga / Novelas**) y permite convertir una de ellas en una app aparte. Al igual que en
Fuentes, hay una sección de ancladas, agrupación por idioma y chips para activar/desactivar idiomas.
Cada extensión ocupa una sola fila aunque exponga varias fuentes. La generación de apps de novelas
todavía no está disponible; la categoría se muestra pero el botón está deshabilitado.

## Qué genera el botón «Crear app»

El generador toma el APK instalado de ExtYomi —es decir, el fork completo— y produce una copia:

- nuevo `packageName` derivado del paquete de la extensión, del `sourceId` y de la huella de la
  clave de firma (así una regeneración no choca con una app antigua firmada por otra clave);
- nombre y icono reales de la extensión: se toma el icono de launcher del paquete de la extensión,
  se sustituyen los PNG del icono y se reescriben `android:icon`/`android:roundIcon` como
  referencias de recurso reales en `<application>` y en todos los alias de launcher;
- `assets/standalone_config.json` con `sourceId`, `kind`, paquete de la extensión, versión y huella
  de su certificado;
- nombres de componentes relativos expandidos antes de cambiar el package, para que sigan
  apuntando a las clases del DEX;
- provider authorities y nombres de permisos reescritos de forma determinista para que la app
  generada pueda convivir con el generador;
- firma con una clave propia creada en el Android Keystore del dispositivo;
- salida por defecto en `standalone/` dentro de la carpeta de almacenamiento ya configurada de
  ExtYomi; el selector SAF sirve solo para cambiar esa carpeta.

No se crea un módulo launcher ni un puente `OPEN_EXTENSION`. La app resultante es el fork, con la
navegación limitada por la configuración embebida.

## Cómo se comporta la app generada

Al arrancar, `StandaloneScope.initializeFromAssets()` lee la configuración y:

- marca la extensión como de confianza en el almacenamiento privado de la nueva app;
- fija el `sourceId` y el `kind` (anime o manga);
- Home muestra solo **Feed** (catálogo de esa fuente), **Biblioteca** y **Más**;
- el catálogo sustituye la flecha de retroceso por el icono de la extensión;
- Biblioteca e Historial se filtran por ese `sourceId`;
- Ajustes → Apariencia incluye «Pantalla de inicio» para elegir entre **Feed (catálogo)** y
  **New Home (biblioteca)**; se ocultan los ajustes de anime/manga/novelas y el selector de icono,
  que no aplican a una app de una sola fuente;
- el apartado «Apps independientes» desaparece.

La app generada usa el sistema de extensiones de Nekoyomi tal cual, pero queda reducida a una sola
fuente. La extensión debe seguir instalada en el dispositivo.

## Seguridad y límites

- El APK se escribe primero en `cacheDir` privado y solo se copia al árbol SAF seleccionado.
- No se redistribuyen APKs de terceros: solo se reempaqueta el propio fork.
- La instalación queda en manos del usuario (el botón descarga/genera, no instala).
- v1 cubre anime y manga; las novelas se listan en su propia categoría pero aún no se pueden generar.
- El recorte de código DEX/Retail no se hace en v1: el aislamiento es de navegación y datos.
