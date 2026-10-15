# Arquitectura de la línea de desarrollo

NeoTransfer separa contratos y reglas de negocio (`core`) de integración Android, persistencia y pantallas (`app`). Esta descripción corresponde a la entrega experimental `1.0.0-dev.6` (`versionCode 8`); no anuncia una release 1.0 ni validación del servidor ENZONA.

```text
Pantallas y acciones (app)
  → USSD: catálogo y codificación (core/catalog), ejecución y correlación SMS (app + core)
  → ENZONA: sesión, APIs de lectura/pago y coordinadores (app/enzona)
  → diarios y datos por canal (app/data + app/enzona/data), cartera y respaldo (app/backup)
```

`OperationCatalog` limita el catálogo USSD a BPA, BANDEC y los servicios Cubacel elegidos. Los catálogos de `core/catalog` conservan contratos e identificadores históricos que no habilitan por sí solos una operación. [Cobertura](cobertura.md) define las exclusiones actuales. Los servicios se agrupan por su propósito; cada canal conserva su identidad y su autorización. No hay reenvío monetario automático ni cambio de ENZONA a USSD ante un fallo.

`EnzonaSessionCoordinator` administra la sesión remota; `EnzonaReadApi`, `EnzonaQrApi` y `EnzonaServiceApi` contienen solicitudes y decoders reconstruidos estáticamente. `EnzonaWalletCoordinator` mantiene lecturas asociadas al titular. El UUID remoto de tarjeta, sus últimos dígitos y un PAN conocido son datos distintos; no se reconstruye un número completo a partir de un UUID o de `last4`.

`EnzonaPaymentCoordinator` conserva el intento revisado antes del envío y distingue confirmado, rechazado e incierto. Los encoders de servicios reciben la contraseña ya cifrada; su autorización no se guarda como parte del intento. Un timeout, una respuesta ambigua o una lectura de saldo no habilitan repetir dinero. El transporte HTTP no renueva la sesión ni reintenta pagos por su cuenta. La biometría local no sustituye la sesión ni la autorización financiera ENZONA.

Para esta entrega, crear la cuenta y vincular inicialmente las tarjetas corresponde a ENZONA oficial. La asociación local de datos de cartera no es alta bancaria nativa. Agua, recarga móvil desde banco y liquidación separada de deuda Hogar mantienen USSD; consultar campos de deuda o recargar Nauta Hogar no demuestra otro contrato de pago.

`OperationExecutor` prepara y envía solicitudes USSD. Autenticación, selección de origen y envío comparten un plazo de 30 segundos. Al agotarse, se guarda `timeoutAt`, se libera la espera local y la interfaz muestra «Tiempo de espera agotado», sin exigir una revisión para continuar. El intento permanece en el diario: no se considera un rechazo ni autoriza un reenvío. Un callback anterior no puede liberar la solicitud siguiente.

La ingesta distingue recepción y marca temporal del centro de mensajes (SMSC); esta última no demuestra la hora exacta de ejecución bancaria. Los comprobantes tardíos siguen procesándose, sin reactivar pagos ni interrumpir la navegación. La correlación exige proveedor, línea, origen y hechos compatibles e inequívocos; la consulta de saldo nunca confirma un pago. Las consultas guardadas pueden conciliarse después de recrear el ejecutor sin reconstruir una sesión autenticada.

Tras confirmar una transferencia, el saldo restante puede actualizar el instrumento identificado por su origen explícito, sin inventar saldo contable ni sustituir evidencia más reciente. Si falta, la ejecución bancaria admite una consulta puntual ligada a ese origen: comparte el plazo original, consume `refreshTaken` antes del envío y no vuelve a programarse al abrir la app. BANDEC exige una prueba todavía válida del origen activo y no vuelve a seleccionarlo automáticamente. Cambiar la tarjeta visible no cambia el origen capturado; retirar la SIM, bloquear o salir cancela la espera local. Este mecanismo no habilita proveedores sin contrato compatible.

`app/data` usa Room para registros, tarjetas, cuentas, operaciones y observaciones USSD. `app/enzona/data` separa lecturas, enlaces de tarjetas e intentos por titular ENZONA. Las credenciales se protegen mediante Android Keystore; los tokens de sesión y cuerpos de autorización no pertenecen a la caché de lecturas ni al diario financiero. `app/backup` crea un respaldo cifrado con contraseña y trata la restauración como un proceso separado de la autorización de acceso. Se preservan datos instalados e históricos; estas rutas no sustituyen pruebas de migración, restauración y actualización.

La recepción de SMS conserva su canal y sus reglas de correlación. La lectura de actividad ENZONA no equivale a entrega de notificaciones: no hay un canal push demostrado para recibirlas con la app cerrada. Las últimas operaciones bancarias tampoco se equiparan al historial de transacciones ENZONA.

La variante `preview` usa datos sintéticos y retira permisos de telefonía, contactos e Internet de su manifiesto. Sirve para revisar pantallas y estados, no para demostrar operaciones reales. La variante `debug` usa otro identificador de aplicación. La firma privada de `release` no forma parte del repositorio ni del CI.

Consulta [cobertura](cobertura.md) para los límites funcionales y [desarrollo](desarrollo.md) para las comprobaciones.
