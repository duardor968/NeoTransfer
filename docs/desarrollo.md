# Desarrollo

## Requisitos

- JDK 21. Sirve el que trae Android Studio (`C:\Program Files\Android\Android Studio\jbr` en Windows); el CI usa Temurin 21. El código compila para Java 17.
- Android SDK 36 (plataforma `android-36`, build-tools 36.0.0). Android Studio lo configura; en terminal basta `sdk.dir` en `local.properties`, que no se sube.
- El wrapper de Gradle incluido (Gradle 8.13). Versiones: AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2026.04.01.

En PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

En Linux o macOS, `./gradlew` con las mismas tareas. Los módulos se describen en [arquitectura](arquitectura.md).

## Pruebas

### JVM

```powershell
.\gradlew.bat :core:test :scanner:testDebugUnitTest :app:testDebugUnitTest
```

- `:core:test`: contratos del protocolo (los 1.400 vectores del codificador, los 800 QR, el orden de parámetros de cada operación y que ninguna produzca `60`, `49`, `58`, `68` ni `69`), plantillas de SMS, escenarios del libro de operaciones, QR y respaldo.
- `:scanner:testDebugUnitTest`: lectura de números, QR en imágenes y seguimiento entre cuadros.
- `:app:testDebugUnitTest`: el controlador con módem y bandeja falsos, las notificaciones y Room.

Las pruebas usan datos inventados. Los SMS de las plantillas son sintéticos.

### Recorrido y capturas

```powershell
.\gradlew.bat :app:testPreviewUnitTest
```

Corre con Robolectric y Roborazzi sobre la variante `preview`:

- `AppJourneyTest` recorre la app integrada con el motor real y el módem, la bandeja y la huella falsos. Guarda cada paso en `app/build/journey/<modo>/`.
- `ScreenshotTest` dibuja cada pantalla del catálogo (`app/src/preview/kotlin/dev/duardo/neotransfer/preview/PreviewCatalog.kt`) en claro, oscuro y claro con letra al 150 %, en `app/build/screenshots/<pantalla>--<modo>.png`.

Las capturas de todas las pantallas tardan. Para regenerar solo algunas, filtra por prefijo del identificador:

```powershell
.\gradlew.bat :app:testPreviewUnitTest --tests '*ScreenshotTest*' -Pscreens=home,transfer,activity
```

### En el teléfono

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest :scanner:connectedDebugAndroidTest
```

Necesitan un teléfono con Android 15 o posterior conectado por ADB, o un emulador ARM de 64 bits: todas las variantes traen bibliotecas nativas solo para `arm64-v8a`. Prueban Room, el controlador con Room real, los puntos de entrada (atajo, mosaico, receptor de SMS y el arranque limpio) y ML Kit con imágenes generadas. No envían USSD ni SMS: el módem y la bandeja son falsos.

El CI (`.github/workflows/android.yml`) ejecuta `:core:test :app:testDebugUnitTest :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest :app:lintDebug`. Compila las pruebas instrumentadas, pero no las ejecuta.

### Con datos reales, solo en local

Dos pruebas leen archivos privados si existe su variable de entorno; si no, no hacen nada. Imprimen solo recuentos y formas enmascaradas.

- `NEOTRANSFER_SMS_DUMP`: un volcado JSON de los SMS de `PAGOxMOVIL`. `RealSmsDumpTest` comprueba que el lector reconoce todos.
- `NEOTRANSFER_TRM`: un respaldo `.trm` de Transfermóvil, para `AppControllerTest`.

Esos archivos nunca van al repositorio.

## Variantes

| Variante | Paquete | Uso |
|---|---|---|
| `debug` | `dev.duardo.neotransfer.debug` | Desarrollo con la SIM y los SMS reales. Se instala al lado de la versión publicada |
| `preview` | `dev.duardo.neotransfer.preview` | Banco simulado: nunca envía USSD ni lee la bandeja real |
| `release` | `dev.duardo.neotransfer` | La que se publica: R8 con reducción de recursos |

### `preview`

`app/src/preview/kotlin/dev/duardo/neotransfer/preview/FakeBank.kt` acusa cada USSD bancario y escribe su SMS de respuesta en una bandeja propia unos segundos después. Los céntimos del importe eligen el desenlace:

- `.13`: rechazada.
- `.44`: «solicitud en proceso».
- `.99`: sin respuesta.
- Cualquier otro: confirmada.

Para simular un abono:

```sh
adb shell am broadcast -n dev.duardo.neotransfer.preview/dev.duardo.neotransfer.preview.FakeSms --es credit <tarjeta> --es amount 300 --es from 5350000009
```

Con `--es body "…"` entrega cualquier texto.

`PreviewActivity` abre una pantalla del catálogo con datos inventados; sin `screen`, lista todas:

```sh
adb shell am start -n dev.duardo.neotransfer.preview/dev.duardo.neotransfer.preview.PreviewActivity --es screen home --es theme DARK --ef fontScale 1.5
```

### `release` y firma

La firma vive fuera del repositorio, en `%USERPROFILE%\.neotransfer\signing.properties` (en Linux o macOS, `~/.neotransfer/signing.properties`):

```properties
storeFile=<ruta al almacén de claves>
storePassword=…
keyAlias=…
keyPassword=…
```

Sin ese archivo, `assembleRelease` produce un APK sin firmar. Todas las versiones se firman con la misma clave para que se instalen encima de la anterior; consérvala junto con su copia.

```powershell
.\gradlew.bat :core:test :scanner:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

## Fondos de tarjeta

`app/src/main/res/drawable-nodpi/card_bpa_red.png` y `card_bandec_red.png` son PNG de 1579 × 996 píxeles, RGB sin transparencia y a sangre: la app redondea las esquinas. Son exportaciones de reconstrucciones vectoriales hechas a partir de fotos; el vector de origen no está en el repositorio.

Para cambiar un fondo, exporta el arte a ese tamaño y formato con el mismo nombre. Número, titular, moneda y vencimiento los dibuja la app en las posiciones de `BpaPrint` y `BandecPrint` (`designsystem/src/main/kotlin/dev/duardo/neotransfer/designsystem/card/CardFace.kt`), en fracciones del ancho y del alto de la tarjeta: si el arte se mueve, recalíbralas ahí y revisa las capturas.

## Contribuir

Ver [CONTRIBUTING](../CONTRIBUTING.md).
