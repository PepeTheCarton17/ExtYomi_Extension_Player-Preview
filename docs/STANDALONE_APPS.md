# Apps independientes

ExtYomi sigue siendo un fork de Nekoyomi. La novedad es el apartado **Explorar → Apps independientes**,
junto a Fuentes, Extensiones y Migrar. Arriba del todo tiene las subcategorías **Anime / Manga /
Novelas**, que solo cambian el tipo de contenido. Debajo, la lista replica exactamente el apartado
**Fuentes**: bloque **Ancladas** primero, después cabeceras por idioma, con el mismo buscador global
(`TravelExplore`) y el mismo filtro (`FilterList`) que Fuentes. No hay chips ni controles propios.
Cada extensión ocupa una sola fila aunque exponga varias fuentes. La generación de apps de novelas
todavía no está disponible; la categoría se muestra pero el botón está deshabilitado.

Las apps generadas quedan registradas en el propio apartado, con una sección **Actualizaciones**
cuando hay una versión nueva del generador y otra **Instaladas**. Generar, instalar, actualizar y
abrir se hacen desde ahí: al instalar aparece el estado **Instalando** y, al terminar, una acción
**Abrir**. El contador de actualizaciones se muestra en la pestaña.

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
- Home muestra **Inicio**, **Catálogo**, **Biblioteca** y **Más**. La casita es siempre el feed y
  nunca la biblioteca; tocarla cuando ya está seleccionada abre los ajustes del feed, como en
  Animetail;
- el catálogo sustituye la flecha de retroceso por el icono de la extensión;
- **Inicio** es un Home con estructura propia, inspirado en el NEW HOME de Animetail: barra superior,
  chips de tipo/formato, carrusel destacado con avance automático y secciones «Continuar viendo y
  leyendo», «Recomendados para ti» y «Populares ahora». Los títulos y las carátulas vienen de la API
  pública de AniList (sin token); el diálogo de ajustes permite ocultar secciones, cambiar el número
  de elementos y decidir si se muestran solo títulos presentes en la fuente. Para comprobar la
  disponibilidad se consulta el catálogo de la extensión en segundo plano, sin insertar nada en la
  biblioteca y sin sustituir las carátulas de la API;
- Biblioteca e Historial se filtran por ese `sourceId`;
- Ajustes → Apariencia incluye «Pantalla de inicio» para elegir entre **Feed (catálogo)** —que abre
  el catálogo de la fuente— y **Inicio nuevo** —que abre el feed de Animetail—. El cambio se aplica
  al instante, sin reiniciar; se ocultan los ajustes de anime/manga/novelas y el selector de icono,
  que no aplican a una app de una sola fuente;
- el apartado «Apps independientes» desaparece, y con él la actualización interna del host: una
  standalone tiene otro paquete y otra firma, así que solo se actualiza desde el generador.

El generador (ExtYomi) también tiene la casita en la barra inferior con el mismo feed global, y
«Buscar actualizaciones» en Ajustes → Acerca de. En Preview la actualización interna se compila con
`-Penable-updater` y apunta a los tags `preview-vX.Y.Z` de este repositorio; el workflow estable no
se ha tocado.

La app generada usa el sistema de extensiones de Nekoyomi tal cual, pero queda reducida a una sola
fuente. La extensión debe seguir instalada en el dispositivo.

## Inicio: filas o catálogo vertical

Cada sección del Home standalone tiene su propio conmutador en la cabecera. La fila horizontal
sigue usando el mismo listing `latest` que el catálogo de la extensión y carga más al llegar al
final; al cambiar a vertical, esa misma sección pasa a un grid paginado construido con
`BrowseAnimeSourceContent` / `BrowseSourceContent` y el filtro de la sección. Tableta muestra unas
9 entradas por línea y el móvil unas 2. El cambio ocurre en el mismo Home, sin abrir otra pantalla.

## Biblioteca, categorías y sincronización

La biblioteca está disponible siempre en la barra de una standalone y no se puede ocultar; el
botón de sincronizar aparece en la barra de biblioteca tanto en ExtYomi como en cada app generada.

- ExtYomi sincroniza con todas las apps registradas; una standalone sincroniza solo con su host.
- Cada app tiene un `instanceId` estable. Dos standalones de la misma extensión —incluso en otro
  idioma— son independientes: las entradas se separan por `kind:sourceId:url` en un índice de
  propietario y una app nunca ve ni sobrescribe las de otra.
- El intercambio usa `ContentProvider.call()` y archivos `content://` para no superar el límite
  de Binder. ExtYomi valida al llamador contra el registro de apps y ese `instanceId`, no contra
  la firma, porque las apps generadas se re-firman con una clave local.
- Cada instantánea incluye entradas, favoritos, categorías por nombre, capítulos/episodios,
  progreso e historial. El merge es LWW por `updatedAt`; las entradas sin cambios conservan su
  marca de tiempo para que un cambio real en cualquiera de las dos apps gane. Las bajas viajan
  como tombstones: quitan la entrada de la biblioteca y sus categorías, pero no borran el historial.
  Las categorías se derivan de las entradas supervivientes, así una categoría desaparece al quitar
  su última entrada de esa extensión.
- Cada capítulo viaja con su título real. Los capítulos sin URL (archivos locales) se identifican
  por su nombre, que es también la clave para encontrar su historial. Aplicar una instantánea nunca
  borra fechas de visionado: solo añade las que trae. Los títulos `/media/...` que dejaron las
  versiones anteriores se reparan en la primera sincronización con esta versión.
- La sincronización es **automática y bidireccional**: ExtYomi hace `pull` de la standalone, fusiona
  con su propia base de datos, aplica el resultado y hace `push` de vuelta. La standalone también
  puede iniciarla con el botón de la biblioteca. El interruptor **Sincronización automática con
  standalone** está en el panel **Filtrar** del Home de ambas apps; cada foreground y cada minuto
  se intenta una sincronización (con un debounce de 20 s).
- Se sincroniza únicamente el ámbito de la app (un `sourceId` y un tipo). Nada de lo que pertenezca
  a otra fuente se toca en ninguno de los dos sentidos. Las apps generadas con versiones anteriores
  no tienen `instanceId` ni los métodos `pullLocal`/`pushLocal`: hay que regenerarlas desde ExtYomi
  con esta versión para que la sincronización automática funcione en ambos sentidos.

## Descargas y carpetas sugeridas

Las descargas se comparten: ExtYomi y la standalone usan el mismo árbol SAF, que se guarda en la
configuración de la app generada y se vuelve a aplicar en el handshake de sincronización. Para
ordenar los APK y el contenido, el generador sugiere estas carpetas dentro del almacenamiento ya
configurado:

- `standalone/anime`
- `standalone/manga`
- `standalone/novelas`

Estas cuatro carpetas se crean al iniciar ExtYomi y al cambiar el almacenamiento base, no solo
cuando se abre la pestaña de generación. La sugerencia no es obligatoria: el diálogo ofrece
**Usar esta ruta** y también permite elegir otra carpeta SAF.

## Seguridad y límites

- El APK se escribe primero en `cacheDir` privado y solo se copia al árbol SAF seleccionado.
- No se redistribuyen APKs de terceros: solo se reempaqueta el propio fork.
- La instalación queda en manos del usuario (el botón descarga/genera, no instala).
- v1 cubre anime y manga; las novelas se listan en su propia categoría pero aún no se pueden generar.
- El recorte de código DEX/Retail no se hace en v1: el aislamiento es de navegación y datos.
