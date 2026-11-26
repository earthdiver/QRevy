"""Extract Android UI strings from this repository without hand-translating them."""
import json,re,sys,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path('..')
base=root/'androidApp/src/main'
def decode(s):
 return re.sub(r'\\(u[0-9a-fA-F]{4}|.)',lambda m: chr(int(m[1][1:],16)) if m[1].startswith('u') else {'n':'\n','t':'\t','r':'\r'}.get(m[1],m[1]),s)
def resources(locale):
 return {e.attrib['name']:decode(''.join(e.itertext())) for e in ET.parse(base/f'res/{locale}/strings.xml').getroot() if e.tag=='string'}
en=resources('values');ja=resources('values-ja')
strings={k:{'ja':ja.get(k,v),'en':v,'source':f'res/values/strings.xml#{k}'} for k,v in en.items()}
pattern=re.compile(r'\b(?:ui|localized|localizedText|label)\(\s*"((?:\\.|[^"\\])*)"\s*,\s*"((?:\\.|[^"\\])*)"\s*\)')
for path in sorted((base/'java').rglob('*.kt')):
 for m in pattern.finditer(path.read_text()):
  a,b=map(decode,m.groups())
  if '$' in a or '$' in b:continue
  strings.setdefault('inline:'+a,{'ja':a,'en':b,'source':str(path.relative_to(base))+':'+str(path.read_text()[:m.start()].count('\n')+1)})
Path('web/native-strings.js').write_text('// Generated from the repository Android UI strings; Apache-2.0. See scripts/extract-ui-strings.py.\nexport const nativeStrings = '+json.dumps(strings,ensure_ascii=False,indent=2)+';\n')
print(f'Extracted {len(strings)} native UI strings')
