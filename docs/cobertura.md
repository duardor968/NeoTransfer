# Cobertura y estado de la versión 1.0 en desarrollo

Esta página describe el alcance del árbol de desarrollo. **No anuncia una versión 1.0.0 publicada ni certifica aceptación de operaciones por bancos o proveedores.** La implementación se debe revisar junto con sus pruebas y fuentes; los resultados del dispositivo y de la red se documentan por operación.

| Área | Presencia en el código de desarrollo | Límite conocido |
| --- | --- | --- |
| BPA, BANDEC y BANMET personales | Catálogos bancarios, cartera, consultas, transferencias, pagos y gestiones modelados por proveedor. | Un formulario o un test de codificación no comprueba aceptación bancaria. Las respuestas tipadas de historial no están demostradas por igual para los tres bancos. |
| MiTransfer y Clásica personal | Catálogos de monedero, transferencias, consultas y servicios. | El perfil agente y Clásica Empresarial están excluidos. Los códigos y confirmaciones dependen del contrato de cada operación. |
| Servicios y pagos | Catálogos de servicios personales, QR bancario, telecomunicaciones y aporte OFA 94. | No se debe extrapolar cobertura a reservas OFA 8/9 ni a Votomóvil 215. Los recibos pueden ser incompletos o ambiguos. |
| MiTurno | Solicitud, consulta, cambio y cancelación modelados; la interfaz usa el diario de solicitudes. | El recibo 102 no demuestra por sí solo proveedor, SIM, cita ni hora asignada. Las respuestas de 105/106/107 requieren comprobación adicional. |
| Combustible | Compra y consultas de cupones, datos protegidos y observaciones en el árbol de desarrollo. | El saldo o uso no se infiere del importe de compra. La aceptación y el historial del proveedor requieren evidencia propia. |
| Cámara | QR y lectura de números impresos mediante CameraX y ML Kit; existen casos instrumentados con imágenes sintéticas. | No se ha demostrado lectura de manuscritos, reflejos o documentos reales; las pruebas instrumentadas requieren ejecución en dispositivo. |
| Contactos y respaldo | Importación de contactos, datos locales, exportación y restauración cifradas. | La compatibilidad con respaldos y datos instalados requiere pruebas de migración y restauración antes de publicar. |

Fuera del alcance 1.0 acordado: **BFI, Bulevar, Clásica Empresarial, MiTransfer agente, MiBoleto completo, reservas OFA 8 y 9, Votomóvil 215**. El **aporte OFA 94** y las demás operaciones personales previstas permanecen dentro del alcance, pero ninguna se considera terminada por el mero hecho de figurar en un catálogo.

Para evaluar una operación concreta se necesitan, por separado: contrato de entrada y salida, validación local, flujo de interfaz, ejecución en dispositivo, tratamiento de rechazo e incertidumbre y, cuando proceda, aceptación real del proveedor. Los últimos resultados de pruebas y publicación deben consultarse en el commit y la release correspondientes.
