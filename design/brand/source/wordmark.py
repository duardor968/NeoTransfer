"""Outline the approved Inter font; requires fontTools (available in WSL)."""
from pathlib import Path
import json
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen

root = Path(__file__).resolve().parents[1]
font = TTFont(root.parents[1] / 'app/src/main/res/font/inter.ttf')
if 'fvar' in font:
    axes = {a.axisTag: a.defaultValue for a in font['fvar'].axes}
    axes['wght'] = 600
    font = instantiateVariableFont(font, axes, inplace=False)
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()
units = font['head'].unitsPerEm
cursor = 0
out = []
for char in 'NeoTransfer':
    name = cmap[ord(char)]
    pen = SVGPathPen(glyphs)
    glyphs[name].draw(pen)
    out.append({'x': cursor, 'd': pen.getCommands()})
    cursor += glyphs[name].width - 14
(root / 'source/wordmark-paths.json').write_text(json.dumps({'units': units, 'width': cursor + 14, 'glyphs': out}, separators=(',', ':')) + '\n')
