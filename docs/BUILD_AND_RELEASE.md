# ExtYomi: compilación y releases

Hay **dos apps independientes**, con distinto `applicationId` y distinta firma:

| Variante | Repo | `applicationId` | Build type | Tag del release | Título |
|---|---|---|---|---|---|
| Preview | `PepeTheCarton17/ExtYomi_Extension_Player-Preview` | `com.pepethecarton17.extyomi.preview` | `preview` | `preview-vX.Y.Z` | `ExtYomi Preview vX.Y.Z` |
| Estable | `PepeTheCarton17/ExtYomi_Extension_Player` | `com.pepethecarton17.extyomi` | `release` | `vX.Y.Z` | `ExtYomi vX.Y.Z` |

## Versiones

`app/build.gradle.kts` (`versionCode` y `versionName`) es la **única fuente de la versión**. El
workflow de Preview lee `versionName` y deriva de él el tag (`preview-vX.Y.Z`) y el título
(`ExtYomi Preview vX.Y.Z`), así la versión que muestra la app, el tag y el título no pueden divergir.
Para publicar una nueva:

1. Actualizar `versionCode` y `versionName` en `app/build.gradle.kts`.
2. Commit y push a `main` en el repo de Preview.

Preview compila en cada push a `main`, pero **solo publica si el tag derivado no existe todavía**:
si el release ya está publicado, se omite la subida en vez de reemplazar los APKs descargados. Por
eso todo merge que deba publicar cambios tiene que subir `versionName`. Estable se publica al
ejecutar el workflow **ExtYomi Stable** manualmente (`workflow_dispatch`) o al pushear un tag `v*`.

## Firmas

Los keystores **no están en el repositorio**. Se generaron como PKCS12 y se guardan como GitHub Secrets en cada repo:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

El workflow decodifica el keystore en `$RUNNER_TEMP` y lo pasa a Gradle mediante la variable de entorno `KEYSTORE_FILE`. `app/build.gradle.kts` define los `signingConfigs` `preview` y `release`.

## Iconos

- Estable: recursos en `app/src/main/res/mipmap-*` — icono del **mouse con triángulo de play**.
- Preview: recursos en `app/src/preview/res/mipmap-*` — icono del **símbolo de pausa**.
- El nombre mostrado se define en `i18n/src/commonMain/moko-resources/base/strings.xml` (`ExtYomi`) y se sobrescribe para Preview en `app/src/preview/res/values/strings.xml` (`ExtYomi Preview`).

## CI

`.github/workflows/build_push.yml` es solo verificación: `spotlessCheck`, `assembleDebug` y tests unitarios. Los builds firmados y los releases los hacen `preview.yml` y `stable.yml`.

`flexible-adapter/` es una copia local (Apache-2.0) del módulo usado por Nekoyomi: el fork de JitPack ya no se puede compilar. Ver `flexible-adapter/README.md`.
