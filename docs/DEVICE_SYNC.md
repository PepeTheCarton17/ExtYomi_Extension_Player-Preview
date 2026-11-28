# Sincronización entre dispositivos (preview)

Está en **Más → Ajustes → Seguimiento → Sincronización entre dispositivos**. Sincroniza la
biblioteca (favoritos, categorías, progreso e historial) entre dos o más instalaciones de ExtYomi.
Los archivos descargados **no** se sincronizan.

## Qué se sincroniza

- Títulos de la biblioteca (con fuente y URL estables), carátula, favoritos y fecha de alta.
- Estado de la obra (`status`) y última actualización conocida (`lastUpdate`), para que el estado
  de "actualizado/finalizado" coincida en ambos dispositivos.
- Capítulos/episodios: vistos o leídos, marcadores, grupos de scanlación, marca de relleno,
  posición, fecha de visionado/lectura, fecha de descubrimiento (`dateFetch`), fecha de subida y
  número de veces visto/leído. La fecha de descubrimiento es la que usa la pestaña
  **Actualizaciones** para decidir qué capítulos aparecen.
- Historial (fecha en que se vio o leyó cada capítulo/episodio).
- Categorías (creación y borrado por nombre).
- Borrados de la biblioteca (tombstones).

Los ajustes de vista/filtros de biblioteca se sincronizan **como una sincronización normal**, sin
interruptor ni confirmación: modo de lista, orden (incluida la dirección), pestañas y badges de
categorías, categorías ocultas, agrupación, columnas, series fijadas/agrupadas y todos los filtros
(no leído, marcador, descargado, iniciado, completado, géneros e intervalo, para anime, manga y
novela).

**No se sincronizan** los archivos descargados. El **decodificador**, el **reproductor interno**, el
**visor/lector** y los **ajustes avanzados** quedan fuera por defecto. Hay una opción
**Sincronizar también ajustes del dispositivo** (desactivada por defecto y con confirmación al
activarla) que los incluye en la sincronización. Hay que activarla en cada dispositivo: si otro
equipo envía estos ajustes y el receptor no los ha habilitado, se ignoran. Sus valores dependen de
los códecs, WSA/emulador, pantalla y controles de cada equipo, por eso no se copian
automáticamente. El propio interruptor es local a cada dispositivo.

## Emparejamiento (un solo enlace)

1. En el dispositivo A, pulsa **Publicar este dispositivo**. Se generan el código de **8 dígitos**,
   un endpoint y un enlace `extyomisync://pair#...` que también se muestra como código QR.
2. En B, escanea ese QR (hay un botón **Escanear QR**) o pega el enlace en el único campo
   **Pega el enlace o el código de 8 dígitos** y pulsa **Unirse**. También funciona un código suelto
   de 8 dígitos si ambos dispositivos están en la misma LAN. Al escanear se rellenan
   automáticamente el servidor relay y el campo del enlace; la contraseña **no** viaja en el QR y
   hay que copiarla del otro dispositivo.
3. El enlace/QR incluye **todas** las direcciones LAN de A (Wi-Fi, hotspot, VPN…) y el id de A, no
   solo una dirección. B envía el `CODE_CONFIRM` al relay si hay uno y, si no, lo intenta contra
   cada dirección hasta que una responde; solo si todas fallan usa el broadcast UDP como última
   chance. Reintenta cada 3 s.
4. A **muestra la contraseña desde el primer momento**, sin esperar a que llegue ninguna
   confirmación: el secreto ya está en A y condicionarlo a un mensaje entrante creaba un bloqueo
   mutuo. El `CONFIRM` sigue enviándose como cortesía, pero no es obligatorio.
5. En B, escribe esa contraseña (acepta letras y números; no hay 0, 1, I ni O) y pulsa el botón de
   contraseña. El **VERIFY cifrado también se reintenta** contra todas las direcciones mientras
   B espera, de modo que un primer envío fallido no deja la contraseña manual sin efecto. B pasa a
   **vinculado en cuanto el VERIFY se entrega**: la prueba es que A pueda descifrarlo con el código
   y la contraseña, no que llegue un `CONFIRM` o un `VERIFY_ACK` posterior.

## WSA / emuladores (sin configurar nada)

Windows Subsystem for Android comparte la IP del PC y **no publica su propio puerto en la LAN**: el
teléfono no puede iniciar una conexión hacia la app dentro de WSA (y el multicast UDP está roto en
WSA). Por eso, si uno de los dos dispositivos es WSA/emulador:

- **Publica desde el teléfono** y escanea ese QR desde WSA. La app detecta WSA y desactiva
  «Publicar este dispositivo» allí, con una explicación en pantalla.
- El `CODE_CONFIRM` y el `VERIFY` salen de WSA hacia el teléfono, que sí son conexiones permitidas.
- El teléfono no puede responder directamente a WSA, así que encola `CONFIRM`, `VERIFY_ACK`, `ACK`
  y `EVENTS` como **ciphertext** en su buzón. WSA los recoge con un `POST /poll` periódico por la
  misma conexión saliente. No hay relay que configurar ni puertos que abrir.

La cola está en memoria, se clavea por id de dispositivo, se deduplica, caduca a los 5 minutos y
solo se borra cuando el receptor confirma los ids recibidos en el siguiente `/poll`. Si WSA se
quedara como anfitrión, el teléfono no podría llegar a él: por eso la inversión de roles es
obligatoria, no opcional.

## Relay (redes distintas)

Es un campo **opcional**: el emparejamiento LAN normal y el caso WSA no lo necesitan. Si A y B no
comparten red, escribe en A la URL de un relay accesible en el campo **Servidor relay**; el enlace
que muestra A ya la incluye y B no tiene que configurar nada. Se incluye uno mínimo en
`tools/device-sync-relay/server.py`:

```bash
python3 tools/device-sync-relay/server.py --port 8789
```

El campo debe valer `http://<host>:8789`. El relay solo ve ciphertext: no puede leer la
biblioteca ni la contraseña.

Cada buzón va dirigido a un destinatario: se publica en `/{pairing|sync}/<codigo>/<id-del-otro>`
y solo ese dispositivo lo drena con su `GET`. Antes había un único buzón por código y el emisor
podía consumir y descartar su propio mensaje mientras sondeaba respuestas: por eso WSA recibía un
cambio una vez y luego ninguno. Los mensajes cifrados también validan el destinatario (`to`) antes
de aplicarse. Hay que instalar la versión corregida en **ambos** dispositivos.

## Cifrado y borrado por ACK

- La clave se deriva con PBKDF2‑HMAC‑SHA256 del código de 8 dígitos y la contraseña de 10 letras
  o números del alfabeto `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`.
  Cada mensaje se cifra con AES‑256‑GCM y un IV aleatorio. La contraseña **nunca** viaja por la red.
- Cada cambio se guarda como un evento pequeño en `device-sync/outbox/` y se envía en lotes de como
  máximo 24 eventos / 256 KB de JSON. Un solo evento con miles de capítulos hizo que
  `Json.encodeToString` intentara reservar ~129 MB de una sola vez y tumbara la app; ahora cada
  sobre cifrado queda por debajo de 1 MB. El cursor de envío se guarda por dispositivo como
  `(createdAt, opId)` del último evento; no depende del archivo, así que los ACKs pueden borrarlo
  sin que el siguiente ciclo vuelva a empezar por el primer lote.
- El receptor guarda cada evento en `device-sync/inbox/` **antes** de aplicarlo y confirma solo los
  que ha aplicado. Los ACKs se agrupan por dispositivo para no desbordar el buzón. Los ACKs que no
  se pueden entregar se guardan y se reintentan en el siguiente ciclo.
- El emisor **borra el archivo del evento solo cuando todos los dispositivos vinculados han
  confirmado**. Si un dispositivo está apagado, su evento permanece en cola y se reenvía al volver.
- La pantalla muestra **Cambios por enviar** (eventos locales sin confirmación de todos) y
  **Cambios por recibir** (eventos remotos guardados en la bandeja y aún no aplicados). No son el
  mismo contador ni tienen por qué coincidir: cada dispositivo mantiene su propia cola.
- Tras reiniciar se deriva la clave de la contraseña guardada localmente, se restaura el emparejo
  como **vinculado** y se reinician el transporte, el sondeo del relay y el buzón, sin depender de
  abrir Ajustes. La dirección del relay del QR también se guarda en el segundo dispositivo.
  Cualquiera de los dos puede dejar respuestas cifradas en el buzón del otro si la conexión directa
  falla: el flujo es bidireccional. La contraseña sigue sin viajar por la red ni aparecer en el QR.
- **Cambios por recibir** se muestra durante ~2 s mientras llega la bandeja y se aplica en el
  siguiente ciclo; si se aplica todo al instante, el contador vuelve a 0. **Cambios por enviar**
  baja cuando llegan los ACK de todos los dispositivos, no cuando se envía el lote.
- La pantalla explica el estado real: «comprobando conexión», «conectado», «esperando ACK»,
  «cambios recibidos», «en el buzón del otro dispositivo» o «sin ruta este ciclo (se reintentará)».
  **Confirmar contraseña** y **Sincronizar ahora** muestran un indicador de carga mientras se
  comprueba el secreto o se recorre la biblioteca. Un fallo parcial (un dispositivo dormido) ya no
  se muestra como «no se pudo conectar» mientras otros cambios sí se entregan.
- Solo queda un cursor de ACK por dispositivo; no se envían instantáneas completas salvo el primer
  emparejamiento y la recuperación.

## Resolución de conflictos

- Progreso: `seen`/`bookmark` se unen (OR) y la posición/fecha se quedan con el valor mayor.
- Favorito y borrados: último cambio por timestamp (LWW).
- Categorías: LWW por nombre, así una eliminación no resucita por un add antiguo.
- Filtros: LWW por clave.
