import zipfile, struct, sys
bad=[]
with zipfile.ZipFile(sys.argv[1]) as z:
 for name in z.namelist():
  if not name.endswith('.so'): continue
  b=z.read(name)
  if b[:4]!=b'\x7fELF': raise ValueError(name)
  is64=b[4]==2
  phoff=struct.unpack_from('<Q' if is64 else '<I',b,32 if is64 else 28)[0]
  size,num=struct.unpack_from('<HH',b,54 if is64 else 42)
  align=[]
  for i in range(num):
   p=phoff+i*size
   if struct.unpack_from('<I',b,p)[0]==1:
    align.append(struct.unpack_from('<Q' if is64 else '<I',b,p+(48 if is64 else 28))[0])
  print(name, [hex(a) for a in align])
  if is64 and any(a<16384 for a in align): bad.append(name)
print('NON_16K:',bad)
sys.exit(bool(bad))

