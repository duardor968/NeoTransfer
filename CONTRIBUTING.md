# Contribuir a NeoTransfer

Se aceptan correcciones de accesibilidad, pruebas, documentación y contratos respaldados por evidencia. Para una función nueva, abre un issue que describa el problema, el comportamiento esperado y la fuente del contrato. Consulta la [cobertura actual](docs/cobertura.md) y el [protocolo](docs/protocolo.md) antes de ampliar un servicio.

## Preparar el proyecto

Instala JDK 21 y Android SDK 36. Abre el repositorio en Android Studio o configura la ruta del SDK en `local.properties`. La firma de producción es privada y no se necesita para compilar `debug` o `preview`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest :app:lintDebug
```

En Windows, usa `gradlew.bat`. La tarea `assembleDebugAndroidTest` solo compila la suite instrumentada: para ejecutarla se necesita un dispositivo o emulador compatible. La [guía de desarrollo](docs/desarrollo.md) separa esas comprobaciones de las pruebas con proveedores.

## Proponer un cambio

- Mantén el cambio enfocado y conserva los avisos de licencia de terceros.
- Acompaña un cambio de contrato con su fuente, casos positivos, rechazos y resultados inciertos. No conviertas un timeout ni la ausencia de comprobante en confirmación o reintento automático de dinero.
- Usa fixtures inventados. No incluyas SMS reales, números personales, PIN, matrices, respaldos, APK ajenos ni material de firma.
- Describe en el PR qué cambió, qué ejecutaste y qué quedó sin comprobar. Una prueba local de codificación no equivale a aceptación bancaria.
- Si cambias una pantalla, revisa ambos temas, texto grande y el flujo en un dispositivo; comparte únicamente imágenes con datos ficticios.

Los textos de producto y la documentación principal están en español. Las contribuciones al código se distribuyen bajo [Apache 2.0](LICENSE); cada dependencia conserva su licencia.
