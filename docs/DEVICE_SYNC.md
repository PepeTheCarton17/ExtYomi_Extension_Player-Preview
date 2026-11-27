# Sincronización entre dispositivos (preview)

Está en **Más → Ajustes → Seguimiento → Sincronización entre dispositivos**. Sincroniza la
biblioteca (favoritos, categorías, progreso e historial) entre dos o más instalaciones de ExtYomi.
Los archivos descargados **no** se sincronizan.

## Emparejamiento manual (misma red)

1. En el dispositivo A, pulsa **Publicar este dispositivo**. Se genera un código de **8 dígitos**
   y se anuncia en la LAN.
2. Comunica el código al usuario del dispositivo B (están en la misma Wi‑Fi).
3. En B, escribe el código de 8 dígitos y pulsa **Emparejar manualmente**; B confirma el código.
4. Cuando A ve el dispositivo B en la lista, lo selecciona y pulsa **Confirmar dispositivos
   seleccionados**. Solo entonces A muestra la contraseña aleatoria de **10 letras**.
5. En B, escribe esa contraseña y confírmala. Quedan vinculados.

## Emparejamiento por QR (redes distintas)

El dispositivo A muestra un QR con el código, su nombre/modelo y su endpoint. B puede escanearlo
con la cámara o pegar el texto del QR. B ve los dispositivos ofrecidos, elige cuál confirmar y
después introduce la contraseña que A revela.

Si los dispositivos **no están en la misma LAN**, configura un servidor relay en el campo
**Servidor relay**. Se incluye uno mínimo en `tools/device-sync-relay/server.py`:

```bash
python3 tools/device-sync-relay/server.py --port 8789
```

El campo debe valer `http://<host>:8789`. El relay solo ve ciphertext: no puede leer la
biblioteca ni la contraseña.

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
