# NeoTransfer — acabado aprobado

Duardo aprobó esta dirección con «Ok, le doy el visto bueno», después de conservar la palomita de la segunda variante y aclarar la N para eliminar sus bandas oscuras repetitivas.

## Entrega

- `source/approved-concept.png`: referencia exacta aprobada, 1536 × 1024.
- `png/neotransfer-textured-{64,128,256,512,1024}.png`: N transparente.
- `png/payment-success-textured-{64,128,256,512,1024}.png`: palomita transparente.
- `android/res/drawable-nodpi/nt_brand_textured.png` y `nt_payment_success_textured.png`: exports 512 px listos para integrar. Copiar a los recursos de la app; no se ha hecho desde esta tarea.
- `svg/`: versiones autocontenidas con los píxeles originales bajo un contorno vectorial. **Son SVG con textura raster embebida**, no texturas vectoriales infinitamente escalables.
- `review/backgrounds.png`: ambos símbolos a 256 y 64 px sobre grafito y blanco.
- `review/validation.json`: dimensiones y verificación de alfa de los diez PNG.

## Reglas de integración

Usar el acabado texturado para marca a tamaño suficiente y el estado de pago exitoso. Para el éxito, mostrar el arte únicamente cuando la lógica de la app haya confirmado ese estado; el asset no cambia la semántica ni confirma por sí mismo una operación USSD/SMS.

Los exports son cuadrados con margen transparente: no aplicar recorte `Crop` ni estirar. Recomendado `Fit`. A 64 px se conserva la silueta, pero el detalle del grano se reduce; para tamaños menores usar los símbolos planos del paquete principal. La palomita no sustituye los iconos funcionales pequeños de la librería.

En fondo blanco las zonas superiores son muy claras. Priorizar este acabado sobre las superficies grafito aprobadas; donde la marca deba tener contraste funcional pequeño, usar la variante plana grafito existente. No oscurecer la textura aprobada para resolverlo sin consultarlo.

El launcher adaptable y la variante monocroma del paquete raíz siguen disponibles como base plana. Estos dos PNG no son capas adaptables de 108 dp ni deben sustituirlas directamente. La tarea principal debe comprobar cualquier integración en el launcher real.

## Producción y validación

La exploración se hizo con ImageGen integrado, con la indicación final de reducir sombras repetidas solo en la N y conservar la palomita. Las extracciones regeneradas añadían halos, por lo que **no se incluyen**: los exports usan los píxeles del concepto aprobado, conservados intactos, dentro de contornos SVG medidos. `source/export.cjs` reproduce el empaquetado y los tamaños con Node + sharp.

Los diez PNG tienen alfa 0–255 y bordes exteriores transparentes. El PNG 1024 es un export ampliado de la referencia; no añade detalle original. Los SVG se han parseado y el resultado se ha inspeccionado en blanco y grafito. Integración, comportamiento del estado de éxito y validación Android real quedan a cargo de la tarea principal.
