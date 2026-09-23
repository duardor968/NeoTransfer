# NeoTransfer — identidad e integración

**Dirección aprobada por Duardo:** [acabado texturado, N aclarada y palomita](approved/README.md). Esa carpeta contiene el arte aprobado y los exports actuales. La base plana descrita abajo se conserva para usos pequeños y monocromos.

**Base plana:** N con dos extremos de flecha opuestos. Une la inicial de NeoTransfer con envío y recepción. Un único trazado, simetría de giro de 180°. Lettering Inter Semibold convertido a curvas; los SVG planos no requieren fuentes instaladas.

## Archivos

- `svg/neotransfer-symbol-{light,dark,graphite,white}.svg`: símbolo transparente, lienzo 72 × 72.
- `svg/neotransfer-wordmark-*.svg`: nombre trazado, 384 × 64.
- `svg/neotransfer-lockup-*.svg`: símbolo y nombre, 466 × 80.
- `png/`: equivalentes transparentes; símbolos de 512 px y marcas a 3×.
- `svg/neotransfer-adaptive-*.svg`: capas de fondo, figura y monocromo a 108 × 108.
- `android/res/`: recursos Android preparados para copiar e integrar por la tarea principal.
- `png/neotransfer-store-icon-512.png`: icono cuadrado opaco para tienda, sin máscara ni sombra incorporadas.
- `review/brand-board.png`: lámina comparativa. `pixel-review.png`: raster a 16/24/32/48 px ampliado 6× sin suavizado. `minimum-lockup.png`: marcas a 156 px reales en ambos fondos.
- `review/validation.json`: medidas de transparencia, dimensiones, área segura y contraste.

## Uso

| Fondo / uso | Variante |
| --- | --- |
| Grafito y superficies oscuras | `dark`: símbolo #3DDC97, nombre blanco |
| Blanco, marca a tamaño normal | `light`: símbolo #1FB36F, nombre #0E0F12 |
| Blanco, símbolo pequeño o funcional | `graphite` |
| Una tinta sobre fondo oscuro | `white` |
| Launcher | Símbolo grafito sobre menta #3DDC97 |

Tamaño recomendado mínimo: símbolo con lienzo de **24 px**; conjunto símbolo y nombre de **156 px de ancho**. A 16 px se identifica la N, pero las flechas pierden definición. Mantener libre alrededor al menos 12 unidades por cada lienzo de símbolo de 72; el margen transparente interno no sustituye esa separación respecto a otros elementos. No estirar ni cambiar el peso del trazado.

Contraste medido: menta/grafito **10,84:1**; menta/#17191E **9,95:1**; menta/#1C1F25 **9,34:1**; grafito/blanco **19,17:1**. El verde aprobado #1FB36F sobre blanco da **2,71:1**: úsese como acento de marca; para texto y símbolos funcionales pequeños está la variante grafito. No se ha alterado la paleta aprobada.

## Android

Copiar el contenido de `android/res/` a los recursos del módulo que lo integre. Los nombres `ic_neotransfer*` evitan sustituir por accidente los recursos provisionales existentes. Después, la tarea principal debe referenciar:

```xml
android:icon="@mipmap/ic_neotransfer"
android:roundIcon="@mipmap/ic_neotransfer_round"
```

Las carpetas `mipmap-anydpi-v26` contienen fondo y foreground. Las `v33` añaden la capa monocroma. Los PNG legacy normal/redondo están en mdpi, hdpi, xhdpi, xxhdpi y xxxhdpi (48/72/96/144/192 px). `drawable/ic_neotransfer_symbol.xml` tiene la marca en grafito, con variante menta en `drawable-night`.

El foreground usa 108 × 108 dp, escala 0,85 y traslación 23,4 de la geometría 72 × 72. Todos sus vértices quedan dentro del círculo seguro de diámetro 66 dp: radio máximo **32,6559 dp**. El fondo es completo, sin máscara. Las vistas de círculo, cuadrado redondeado y squircle de la lámina son simulaciones. Referencia: [Android Developers: Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

## Validación y alcance

24 PNG de entrega comprobados; los símbolos, nombres, conjuntos y foreground tienen alfa 0–255 y bordes exteriores transparentes. XML y SVG parseados. Revisión visual independiente: pasa en alineación, recortes, peso, reconocimiento, alcance y conservación de la dirección aprobada; límite de 16 px descrito arriba.

No se ha editado la app, ejecutado Gradle, conectado ADB ni instalado un APK. **Pendiente de la tarea principal:** integración y comprobación del launcher real. El acabado texturado en `approved/` fue aprobado explícitamente; no volver a integrar la lámina plana como única dirección visual.

## Regeneración

`source/build.cjs` genera los assets con Node y `sharp`; `source/verify.cjs` produce la validación. Resolver `sharp` desde las dependencias del entorno mediante `NODE_PATH`. Los contornos tipográficos están en `source/wordmark-paths.json`; `source/wordmark.py` los regenera con fontTools desde `app/src/main/res/font/inter.ttf` (Inter, peso 600). La licencia se conserva en `source/Inter-OFL.txt`. No se redistribuye otra fuente.
