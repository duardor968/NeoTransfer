# Seguridad

Se atienden reportes sobre la última versión publicada en [Releases](https://github.com/duardor968/NeoTransfer/releases) y sobre la rama `dev`.

## Reportar una vulnerabilidad

Usa [Report a vulnerability](https://github.com/duardor968/NeoTransfer/security/advisories/new) para comunicarla en privado. Si esa opción no está disponible, abre un issue sin detalles explotables y pide un canal privado. No publiques secretos ni datos bancarios en issues o PR.

Indica la versión o el commit, los pasos para reproducirla con datos inventados, lo que esperabas y el impacto. No envíes claves, PIN, números de tarjeta reales, SMS personales, respaldos ni archivos de firma. La respuesta va por el canal privado; no hay un plazo fijo.

## APK oficiales

Los APK oficiales solo se publican en [Releases](https://github.com/duardor968/NeoTransfer/releases), siempre firmados con la misma clave, que vive fuera del repositorio y del CI. Cada publicación indica la suma SHA-256 del APK. Android no instala una actualización firmada con otra clave encima de la que tienes.

Cómo protege la app las claves del banco, el bloqueo y las notificaciones: [arquitectura](docs/arquitectura.md#seguridad).
