# Sincronización entre dispositivos (preview)

Está en **Más → Ajustes → Seguimiento → Sincronización entre dispositivos**. Sincroniza la
biblioteca (favoritos, categorías, progreso e historial) entre dos o más instalaciones de ExtYomi.
Los archivos descargados **no** se sincronizan.

## Qué se sincroniza

- Títulos de la biblioteca (con fuente y URL estables), carátula, favoritos y fecha de alta.
- Estado de la obra (`status`) y última actualización conocida (`lastUpdate`), para que el estado
  de "actualizado/finalizado" coincida en ambos dispositivos.
- Capítulos/episodios: vistos o leídos, marcadores, grupos de scanlación, marca de relleno,
  posición, fecha de visionado/lectura, fecha de subida y número de veces visto/leído.
- Historial (fecha en que se vio o leyó cada capítulo/episodio).
- Categorías (creación y borrado por nombre).
- Borrados de la biblioteca (tombstones).

**No se sincronizan** los archivos descargados, ni el resto de ajustes de la app. Hay una opción
**Sincronizar también algunos ajustes de biblioteca** (desactivada por defecto) que comparte solo
una lista reducida de preferencias de vista/filtros de biblioteca: modo de lista, orden, pestañas
y badges de categorías, columnas y filtros de no leído/marcador. El propio interruptor es local a
cada dispositivo.

## Emparejamiento (un solo enlace)

1. En el dispositivo A, pulsa **Publicar este dispositivo**. Se generan el código de **8 dígitos**,
   un endpoint y un enlace `extyomisync://pair#...` que también se muestra como código QR.
2. En B, escanea ese QR (hay un botón **Escanear QR**) o pega el enlace en el único campo
   **Pega el enlace o el código de 8 dígitos** y pulsa **Unirse**. También funciona un código suelto
   de 8 dígitos si ambos dispositivos están en la misma LAN.
3. B envía la confirmación directamente al endpoint (o al relay) que viene dentro del enlace, así
   que A la recibe sin depender de broadcasts. A confirma automáticamente al primer dispositivo,
   le muestra a B el mensaje **Código confirmado** y revela su contraseña aleatoria de **10 letras**.
   Si se unen más dispositivos, A puede seleccionar cuáles confirmar antes de mostrar la contraseña.
4. En B, escribe esa contraseña y pulsa el botón de contraseña. Quedan vinculados.

## Relay (redes distintas)

Si A y B no comparten red, escribe en A la URL de un relay accesible en el campo **Servidor relay**;
el enlace que muestra A ya la incluye y B no tiene que configurar nada. Se incluye uno mínimo en
`tools/device-sync-relay/server.py`:

```bash
python3 tools/device-sync-relay/server.py --port 8789
```

El campo debe valer `http://<host>:8789`. El relay solo ve ciphertext: no puede leer la
biblioteca ni la contraseña. Sin relay, el emparejamiento directo requiere que ambos dispositivos
puedan alcanzarse por la LAN (o que el endpoint publicado sea accesible).

## Cifrado y borrado por ACK

- La clave se deriva con PBKDF2‑HMAC‑SHA256 del código de 8 dígitos y la contraseña de 10 letras.
  Cada mensaje se cifra con AES‑256‑GCM y un IV aleatorio. La contraseña **nunca** viaja por la red.
- Cada cambio se guarda como un evento pequeño en `device-sync/outbox/`.
- El receptor aplica el evento y devuelve un ACK con el `opId`.
- El emisor **borra el archivo del evento solo cuando todos los dispositivos vinculados han
  confirmado**. Si un dispositivo está apagado, su evento permanece en cola y se reenvía al volver.
- Solo queda un cursor de ACK por dispositivo; no se envían instantáneas completas salvo el primer
  emparejamiento y la recuperación.

## Resolución de conflictos

- Progreso: `seen`/`bookmark` se unen (OR) y la posición/fecha se quedan con el valor mayor.
- Favorito y borrados: último cambio por timestamp (LWW).
- Categorías: LWW por nombre, así una eliminación no resucita por un add antiguo.
- Filtros: LWW por clave.
