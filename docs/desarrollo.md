# Desarrollo y comprobaciones

Requisitos: JDK 21, Android SDK 36 y el wrapper Gradle incluido. Android Studio puede configurar el SDK; en terminal se puede indicar su ruta en `local.properties` sin subir ese archivo. Las compilaciones `debug` y `preview` no requieren la firma privada de producción.

Base de esta entrega: `1.0.0-dev.6`, `versionCode 8`. Es una prueba experimental de ENZONA, no una release 1.0. Las APIs implementadas se apoyan en contratos estáticos y fixtures sintéticos; todavía no hay una cuenta ENZONA probada ni operaciones de dinero por ese canal. Las comprobaciones reales previas BPA/BANDEC mantienen su alcance propio en [verificación](verificacion.md) y [protocolo](protocolo.md).

La QA automatizada usa datos sintéticos y no realiza operaciones bancarias. Para la prueba real, el titular creará la cuenta y vinculará primero las tarjetas en ENZONA oficial. El registro de cuenta y esa primera vinculación son prerrequisitos de esta prueba; el alta nativa de tarjetas queda aplazada. No deben presentarse como una función disponible en NeoTransfer.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest :app:lintDebug
```

En PowerShell usa `.\gradlew.bat` con las mismas tareas. `:core:test` comprueba contratos y lógica JVM; `:app:testDebugUnitTest` comprueba lógica Android que admite pruebas locales. `assembleDebug`, `assemblePreview` y `assembleDebugAndroidTest` compilan artefactos; `lintDebug` analiza la variante `debug`. Compilar el APK de pruebas **no ejecuta** los casos instrumentados.

Para ejecutar los casos instrumentados en un dispositivo Android 15 o posterior, instala los APK `debug` y `androidTest` recién compilados y lanza el runner propio:

```sh
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.duardo.neotransfer.debug.test/dev.duardo.neotransfer.BankingChecks
```

Comprueba en la salida los casos `PASS` y el resultado final del runner; cualquier `FAIL` exige revisión. Este flujo de ADB se ha usado con el runner `BankingChecks`. La tarea Gradle `connectedDebugAndroidTest` no está validada para este runner propio. El CI público solo compila el APK instrumentado: no ejecuta la suite en un dispositivo ni comprueba aceptación bancaria.

El contenido de las notificaciones se omite con `SKIP` si Android o la aplicación las tienen deshabilitadas. Para repetir solo ese bloque con el permiso habilitado en la aplicación de pruebas, usa `adb shell am instrument -w -e suite notifications dev.duardo.neotransfer.debug.test/dev.duardo.neotransfer.BankingChecks`. Restaura cualquier permiso modificado al terminar; el caso elimina su aviso sintético.

Los casos de ENZONA deben usar HTTP simulado y datos inventados: separación de titulares, caché e historial, wrappers de consulta, campos de deuda y autorización, respuestas aceptadas/pendientes/fallidas y pérdida de transporte. Un intent vacío/null no debe anular por sí solo una factura válida; una respuesta ambigua tampoco confirma dinero. Comprobar que renovar sesión o volver a abrir la aplicación no repite un pago ni cambia automáticamente a USSD. Estas pruebas no certifican el servidor.

La prueba de notificaciones SMS no demuestra push ENZONA con la app cerrada. Ese comportamiento sigue pendiente y debe medirse por separado. La recepción de SMS y sus comprobantes existentes se conserva.

Antes de considerar una publicación, comprueba por separado:

1. Pruebas JVM, compilación, lint y suite instrumentada del commit que se publicará.
2. Actualización de la aplicación principal instalada conservando sus datos y con la misma firma de distribución; migraciones y restauración con casos positivos y de rechazo.
3. Flujos visibles en ambos temas y con texto grande, permisos, biometría, cámara y cambios de estado.
4. Contratos y respuestas de proveedores para las operaciones que se anuncien; un acuse, timeout o fixture local no demuestra una transacción aceptada.
5. APK firmado, suma SHA-256, notas de versión y correspondencia entre tag, código y artefacto de GitHub Releases.

No incluyas APK o DEX de terceros, trazas personales, certificados privados, claves, tokens, credenciales ni SMS reales en el repositorio, PR o CI. Las pruebas financieras reales requieren al titular y autorización concreta; no forman parte de la QA sintética. La variante `preview` trabaja con datos inventados y sin los permisos de telefonía, contactos e Internet del manifiesto principal. Para un contrato nuevo, documenta la fuente y los límites de evidencia en el PR y actualiza la [cobertura](cobertura.md). Los resultados medidos pertenecen a [verificación](verificacion.md), sin deducir aceptación de un build o de un test local.
