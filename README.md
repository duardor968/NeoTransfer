<p align="center">
  <img src="design/brand/approved/png/neotransfer-textured-256.png" width="112" alt="NeoTransfer">
</p>

# NeoTransfer

Una cartera multibanco para Android, diseñada para gestionar tarjetas, pagos y servicios en Cuba desde una interfaz clara.

**La versión 1.0 está en desarrollo.** Este repositorio parte de NeoTransfer 0.2.0. La cartera multibanco, el catálogo completo y las nuevas funciones de cámara se incorporarán durante el refactor; todavía no hay una release pública de la 1.0.

NeoTransfer es un cliente independiente, sin afiliación con ETECSA, Transfermóvil ni las entidades financieras. Utiliza los servicios disponibles para la línea y los productos registrados por su titular.

## Qué puedes encontrar hoy

La base 0.2 incluye acceso protegido por biometría, consulta de saldo, formularios de transferencia, recarga móvil, electricidad/teléfono, lectura QR e historial para BPA y BANDEC. La consulta de saldo se ha probado con una cuenta real; las pruebas locales no demuestran aceptación bancaria de todas las operaciones.

## Hacia la 1.0

- Cartera con tarjetas de varios bancos y selección directa de origen.
- Contactos con varias tarjetas y teléfonos, actividad y servicios organizados.
- Catálogo de bancos, monederos, pagos y gestiones de Transfermóvil 1.260416.
- Cámara QR y lectura de números impresos o manuscritos.
- Avisos de transferencias, importación y respaldos cifrados.

## Requisitos

- Android 15 o posterior.
- Biometría fuerte configurada para proteger los accesos bancarios.
- Una línea y un registro compatibles con el servicio utilizado. Las operaciones USSD requieren cobertura celular.

## Descargar

El APK firmado de la 1.0 se publicará en [Releases](https://github.com/duardor968/NeoTransfer/releases), junto con su SHA-256 y notas de versión.

## Para desarrollar

Proyecto nativo con Kotlin y Jetpack Compose; módulos `app` y `core`. Se necesita Java 21 y Android SDK 36.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

Las credenciales de firma de producción son locales y no se requieren para compilar la variante de desarrollo. Los fixtures del repositorio son sintéticos.

El [protocolo documentado](docs/protocolo.md) distingue contratos extraídos del cliente de resultados comprobados en el banco. No contiene el APK ni el código descompilado de Transfermóvil.

## Licencia

El código de NeoTransfer se publica bajo [Apache 2.0](LICENSE). Las dependencias, fuentes y recursos de terceros conservan sus respectivas licencias y marcas.
