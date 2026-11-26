# New Home: optional content-format metadata

The new Home builds `Películas` / `Series` rows and format chips for anime, manga, manhwa,
webtoon, comic, light novel and webnovel. It always works without any extension change: AniList's
own `format` and the genre/filter names are used as a fallback.

Extension authors can opt in with accurate metadata by exposing **either** of these on the source
object returned by the extension:

```kotlin
fun getContentFormats(): Collection<String> = listOf("MOVIE")
// or
@JvmField val contentFormats: Array<String> = arrayOf("MANHWA")
```

A comma-separated `String` is accepted as well. Recognised values (case-insensitive):

| Value | Meaning |
| --- | --- |
| `ANIME`, `SERIES`, `MOVIE` | video formats |
| `MANGA`, `MANHWA`, `WEBTOON`, `COMIC` | illustrated formats |
| `LIGHT_NOVEL`, `WEBNOVEL` | written formats |

Values coming from genres or filter names are also understood in Spanish/English (for example
`Películas`, `Movie`, `Serie`, `Webtoon`, `Novela ligera`). Nothing is required, and an exception in
the hook is logged and ignored instead of breaking the feed.

The hook is read by `eu.kanade.tachiyomi.data.home.HomeFeedFormatMetadata`, which keeps the host
decoupled from extension classloaders. Source priority for these rows is configured in
**Ajustes del feed → Ajustes → Solo títulos de estas fuentes …**, independently of migration and
player fallback priorities.
