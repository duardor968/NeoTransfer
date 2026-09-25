# Arquitectura de la línea de desarrollo

NeoTransfer separa contratos y reglas de negocio (`core`) de integración Android, persistencia y pantallas (`app`). Esta descripción corresponde al árbol de desarrollo; no es una lista de funciones publicadas en 0.2.0.

```text
Pantallas y acciones (app)
  → catálogo, validación y codificación de operaciones (core/catalog)
  → ejecución USSD/SMS y correlación de respuestas (app + core)
  → diario, cartera y respaldo (app/data + app/backup)
```

`OperationCatalog` reúne las operaciones expuestas por la aplicación y bloquea BFI, Clásica Empresarial y MiTransfer agente. Los catálogos de `core/catalog` modelan proveedor, perfil, campos, origen, moneda, transporte y efectos. `docs/protocolo.md` explica de dónde proceden los contratos. Un contrato describe cómo construir una solicitud; no prueba que el proveedor la acepte hoy.

`OperationExecutor` prepara y envía la solicitud por el transporte correspondiente. Los parsers y la correlación de recibos examinan las respuestas. Una respuesta tardía, ausente o ambigua puede dejar la operación como incierta; la aplicación no debe convertir esa incertidumbre en éxito ni repetir automáticamente un movimiento de dinero.

`app/data` usa Room para registros, tarjetas, cuentas, operaciones y observaciones. El acceso a credenciales bancarias y a secretos de cupones se apoya en Android Keystore y biometría. `app/backup` crea un respaldo cifrado con contraseña y trata la restauración como un proceso separado de la autorización de acceso. La existencia de estas rutas en código no sustituye las pruebas de migración, restauración y actualización de la aplicación instalada.

La variante `preview` usa datos sintéticos y retira permisos de telefonía, contactos e Internet de su manifiesto. Sirve para revisar pantallas y estados, no para demostrar operaciones reales. La variante `debug` usa otro identificador de aplicación. La firma privada de `release` no forma parte del repositorio ni del CI.

Consulta [cobertura](cobertura.md) para los límites funcionales y [desarrollo](desarrollo.md) para las comprobaciones.
