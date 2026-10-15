# Contribuir a NeoTransfer

Se aceptan correcciones, pruebas, accesibilidad, documentación y contratos respaldados por evidencia. Para una función nueva, abre antes un issue con el problema, el comportamiento esperado y la fuente del contrato. El alcance de la 1.0 es BPA, BANDEC y los servicios del [README](README.md#qué-hace); el [protocolo](docs/protocolo.md) dice de dónde sale cada formato.

## Preparar el proyecto

Necesitas JDK 21 (sirve el de Android Studio) y Android SDK 36. Abre el repositorio en Android Studio o pon la ruta del SDK en `local.properties`. La firma de producción es privada y no hace falta para `debug` ni `preview`.

Lo mismo que comprueba el CI:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest :app:lintDebug
```

En Windows, `.\gradlew.bat`. Las pruebas del escáner, las capturas y las pruebas en el teléfono están en [desarrollo](docs/desarrollo.md#pruebas).

## Proponer un cambio

- Mantén el cambio enfocado y conserva los avisos de licencia de terceros.
- Acompaña un cambio de contrato con su fuente y con casos de éxito, rechazo y sin respuesta. Ni un acuse USSD ni la falta de respuesta confirman una operación, y el dinero nunca se reenvía solo.
- Usa datos inventados. No incluyas SMS reales, números personales, claves, respaldos, APK de terceros ni material de firma.
- Describe en el PR qué cambió, qué ejecutaste y qué quedó sin comprobar. Una prueba local no equivale a que el banco acepte la operación.
- Si cambias una pantalla, revisa las capturas en claro, oscuro y con letra al 150 % (`ScreenshotTest`) y el flujo en un teléfono; comparte solo imágenes con datos inventados.

Los textos de la app y la documentación están en español. Las contribuciones se distribuyen bajo [Apache 2.0](LICENSE); cada dependencia conserva su licencia.

## Integrar en `main`

El desarrollo va en `dev`. `main` solo avanza con `git merge --ff-only dev` desde `main`, seguido del push. El PR sirve para revisar; no se usa squash, rebase ni commit de merge.
