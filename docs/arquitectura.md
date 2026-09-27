# Arquitectura de la línea de desarrollo

NeoTransfer separa contratos y reglas de negocio (`core`) de integración Android, persistencia y pantallas (`app`). Esta descripción corresponde al árbol de desarrollo; no es una lista de funciones publicadas en 0.2.0.

```text
Pantallas y acciones (app)
  → catálogo, validación y codificación de operaciones (core/catalog)
  → ejecución USSD/SMS y correlación de respuestas (app + core)
  → diario, cartera y respaldo (app/data + app/backup)
```

`OperationCatalog` reúne las operaciones expuestas por la aplicación y bloquea BFI, Clásica Empresarial y MiTransfer agente. Los catálogos de `core/catalog` modelan proveedor, perfil, campos, origen, moneda, transporte y efectos. `docs/protocolo.md` explica de dónde proceden los contratos. Un contrato describe cómo construir una solicitud; no prueba que el proveedor la acepte hoy.

`OperationExecutor` prepara y envía la solicitud por el transporte correspondiente. Autenticación, selección de origen y envío comparten un plazo de 30 segundos. Al agotarse, se guarda `timeoutAt`, se libera la espera local y la interfaz muestra «Tiempo de espera agotado», sin exigir una revisión para continuar. El intento permanece en el diario: no se considera un rechazo ni autoriza un reenvío. Un callback anterior no puede liberar la solicitud siguiente.

La ingesta distingue recepción y marca temporal del centro de mensajes (SMSC); esta última no demuestra la hora exacta de ejecución bancaria. Los comprobantes tardíos siguen procesándose, sin reactivar pagos ni interrumpir la navegación. La correlación exige proveedor, línea, origen y hechos compatibles e inequívocos; la consulta de saldo nunca confirma un pago. Las consultas guardadas pueden conciliarse después de recrear el ejecutor sin reconstruir una sesión autenticada.

Tras confirmar una transferencia, el saldo restante puede actualizar el instrumento identificado por su origen explícito, sin inventar saldo contable ni sustituir evidencia más reciente. Si falta, la ejecución bancaria admite una consulta puntual ligada a ese origen: comparte el plazo original, consume `refreshTaken` antes del envío y no vuelve a programarse al abrir la app. BANDEC exige una prueba todavía válida del origen activo y no vuelve a seleccionarlo automáticamente. Cambiar la tarjeta visible no cambia el origen capturado; retirar la SIM, bloquear o salir cancela la espera local. Este mecanismo no habilita proveedores sin contrato compatible.

`app/data` usa Room para registros, tarjetas, cuentas, operaciones y observaciones. El acceso a credenciales bancarias y a secretos de cupones se apoya en Android Keystore y biometría. `app/backup` crea un respaldo cifrado con contraseña y trata la restauración como un proceso separado de la autorización de acceso. La existencia de estas rutas en código no sustituye las pruebas de migración, restauración y actualización de la aplicación instalada.

La variante `preview` usa datos sintéticos y retira permisos de telefonía, contactos e Internet de su manifiesto. Sirve para revisar pantallas y estados, no para demostrar operaciones reales. La variante `debug` usa otro identificador de aplicación. La firma privada de `release` no forma parte del repositorio ni del CI.

Consulta [cobertura](cobertura.md) para los límites funcionales y [desarrollo](desarrollo.md) para las comprobaciones.
