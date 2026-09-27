# Desarrollo y comprobaciones

Requisitos: JDK 21, Android SDK 36 y el wrapper Gradle incluido. Android Studio puede configurar el SDK; en terminal se puede indicar su ruta en `local.properties` sin subir ese archivo. Las compilaciones `debug` y `preview` no requieren la firma privada de producción.

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

Antes de considerar una publicación, comprueba por separado:

1. Pruebas JVM, compilación, lint y suite instrumentada del commit que se publicará.
2. Actualización de la aplicación principal instalada conservando sus datos y con la misma firma de distribución; migraciones y restauración con casos positivos y de rechazo.
3. Flujos visibles en ambos temas y con texto grande, permisos, biometría, cámara y cambios de estado.
4. Contratos y respuestas de proveedores para las operaciones que se anuncien; un acuse, timeout o fixture local no demuestra una transacción aceptada.
5. APK firmado, suma SHA-256, notas de versión y correspondencia entre tag, código y artefacto de GitHub Releases.

No uses credenciales, SMS ni pagos reales en PR o CI. La variante `preview` trabaja con datos inventados y sin los permisos de telefonía, contactos e Internet del manifiesto principal. Para un contrato nuevo, documenta la fuente y los límites de evidencia en el PR y actualiza la [cobertura](cobertura.md).
