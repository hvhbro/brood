# RustMe decryptor + deobf.
# by plakceldlc team
from pathlib import Path
KEY=bytes.fromhex('1ec8ca803504eedbcb10bde7570b6e')
OPS={
'minor':(16,[(0,52032),(0,1276),(1,51871)]),
'major':(16,[(0,40039),(2,28200),(1,59236)]),
'cp_count':(16,[(2,12378),(0,28962),(2,532)]),
'class_idx':(16,[(0,26921),(2,56801),(1,2154)]),
'fieldref_1':(16,[(0,32677),(0,55313),(1,26730)]),
'fieldref_2':(16,[(0,7065),(1,38662),(1,25993)]),
'methodref_1':(16,[(2,26115),(0,55223),(1,31180)]),
'methodref_2':(16,[(2,32279),(2,21866),(0,44574)]),
'imethodref_1':(16,[(1,51319),(0,43568),(2,39168)]),
'imethodref_2':(16,[(0,11228),(2,57332),(0,32067)]),
'string_idx':(16,[(0,15694),(1,41638),(0,42199)]),
'mh_kind':(8,[(2,40),(0,194),(1,44)]),
'mh_idx':(16,[(1,21712),(1,59600),(0,61750)]),
'mtype_idx':(16,[(1,36867),(0,27698),(2,21088)]),
'indy_1':(16,[(0,1062),(2,7893),(0,25563)]),
'indy_2':(16,[(1,61642),(0,62102),(1,21549)]),
'int':(32,[(1,548250857),(0,3972430384),(0,2405038282)]),
'float':(32,[(1,293022150),(2,1424777783),(0,4241655426)]),
'long_low':(32,[(1,1070007818),(0,3885934546),(1,2748706707)]),
'long_high':(32,[(0,2002681555),(0,4012924538),(1,1377767067)]),
'double_low':(32,[(0,866420649),(2,2637259444),(1,481022249)]),
'double_high':(32,[(1,425863830),(0,737228446),(0,1119455560)]),
'nat_1':(16,[(1,6044),(2,22107),(0,5451)]),
'nat_2':(16,[(1,58278),(1,2955),(2,8404)]),
'utf_len':(16,[(1,10346),(0,58003),(0,44499)]),
'utf_byte':(8,[(1,183),(0,177),(0,38)]),
'class_access':(16,[(0,56814),(2,62091),(1,47384)]),
'class_this':(16,[(2,16964),(2,21261),(0,41413)]),
'class_super':(16,[(0,26523),(2,55049),(2,29851)]),
'class_icount':(16,[(2,789),(1,32112),(0,4737)]),
'interface_idx':(16,[(0,25195),(2,45650),(0,6288)]),
'fields_count':(16,[(0,6036),(2,29959),(0,7083)]),
'field_access':(16,[(0,27339),(2,57710),(2,55063)]),
'field_name':(16,[(2,22019),(1,39663),(0,15202)]),
'field_desc':(16,[(0,45930),(1,36100),(2,25)]),
'field_acount':(16,[(0,30369),(2,63559),(1,39502)]),
'methods_count':(16,[(1,22860),(1,22237),(1,9496)]),
'method_access':(16,[(0,22284),(2,1774),(1,49578)]),
'method_name':(16,[(0,9147),(0,25524),(1,51688)]),
'method_desc':(16,[(2,42832),(2,38704),(1,49283)]),
'method_acount':(16,[(0,19690),(1,53799),(0,48427)]),
'method_attr_name':(16,[(2,281),(0,7788),(0,56489)]),
'method_attr_len':(32,[(0,533737621),(0,2429553169),(0,374638732)]),
'code_max_stack':(16,[(0,46313),(0,48655),(2,51298)]),
'code_max_locals':(16,[(2,37784),(0,61153),(1,21721)]),
'code_len':(32,[(0,1435738375),(1,3717599135),(1,518751669)]),
'class_acount':(16,[(0,32045),(0,7),(2,50114)]),
'class_attr_name':(16,[(2,31208),(1,13307),(0,19855)]),
'class_attr_len':(32,[(0,3800826055),(0,1396250349),(0,433305010)]),

'field_attr_name':(16,[(0,0xfd42),(0,0x765c),(0,0x3532)]),
'field_attr_len':(32,[(0,0xaf3c2cb7),(1,0xb26c075f),(0,0x426ede1b)]),
}
TAG={0xf1:1,0xf0:3,0xf3:4,0xd7:5,0xf6:6,0xf5:7,0xe9:8,0xe7:9,0xda:10,0xee:11,0xf2:12,0xea:15,0xec:16,0xf4:18}
OPMAP = {
    0x00: 0x51,
    0x01: 0x12,
    0x02: 0x8D,
    0x03: 0x1F,
    0x04: 0x05,
    0x05: 0xB9,
    0x06: 0x52,
    0x07: 0x48,
    0x08: 0x0F,
    0x09: 0x34,
    0x0A: 0x00,
    0x0B: 0x90,
    0x0C: 0x0E,
    0x0D: 0xB1,
    0x0E: 0x24,
    0x0F: 0x0B,
    0x10: 0x14,
    0x11: 0x2C,
    0x12: 0xA7,
    0x13: 0x70,
    0x14: 0x0A,
    0x15: 0x65,
    0x16: 0x61,
    0x17: 0x57,
    0x18: 0x76,
    0x19: 0xB8,
    0x1A: 0x2D,
    0x1B: 0x6D,
    0x1C: 0x25,
    0x1D: 0x17,
    0x1E: 0x26,
    0x1F: 0x16,
    0x20: 0x56,
    0x21: 0x35,
    0x22: 0x2A,
    0x23: 0x7B,
    0x24: 0x0D,
    0x25: 0x91,
    0x26: 0x38,
    0x27: 0x7E,
    0x28: 0x75,
    0x29: 0x83,
    0x2B: 0x18,
    0x2C: 0xA6,
    0x2D: 0x87,
    0x2E: 0x6C,
    0x2F: 0xBD,
    0x30: 0x6B,
    0x31: 0x3E,
    0x32: 0x99,
    0x33: 0xB0,
    0x34: 0xB7,
    0x35: 0x98,
    0x36: 0x36,
    0x37: 0x77,
    0x38: 0x9F,
    0x39: 0x2E,
    0x3A: 0x42,
    0x3B: 0xB3,
    0x3C: 0xA2,
    0x3D: 0x7F,
    0x3E: 0x3A,
    0x3F: 0x78,
    0x40: 0x9B,
    0x41: 0x80,
    0x42: 0x23,
    0x43: 0x63,
    0x44: 0x94,
    0x45: 0x4D,
    0x46: 0x4F,
    0x47: 0x28,
    0x48: 0x95,
    0x49: 0xA3,
    0x4A: 0x8B,
    0x4B: 0x8A,
    0x4C: 0x3C,
    0x4D: 0x13,
    0x4E: 0xAF,
    0x4F: 0x33,
    0x50: 0x07,
    0x51: 0x47,
    0x52: 0x06,
    0x53: 0x27,
    0x54: 0x7A,
    0x55: 0x30,
    0x56: 0xAB,
    0x57: 0xB6,
    0x58: 0xA5,
    0x5A: 0x69,
    0x5B: 0x29,
    0x5C: 0x32,
    0x5D: 0x09,
    0x5E: 0x4E,
    0x5F: 0x4B,
    0x60: 0x5B,
    0x61: 0x1E,
    0x62: 0x0C,
    0x63: 0x72,
    0x64: 0xC6,
    0x65: 0x4C,
    0x66: 0x64,
    0x67: 0x1B,
    0x68: 0x89,
    0x69: 0x7C,
    0x6A: 0x08,
    0x6B: 0x81,
    0x6C: 0xC2,
    0x6D: 0x67,
    0x6E: 0x5F,
    0x6F: 0x59,
    0x70: 0xBE,
    0x71: 0x84,
    0x72: 0x40,
    0x73: 0x03,
    0x74: 0x3F,
    0x75: 0xBC,
    0x76: 0xC7,
    0x77: 0x1C,
    0x78: 0xBB,
    0x79: 0xAD,
    0x7A: 0x71,
    0x7B: 0x04,
    0x7C: 0x46,
    0x7D: 0x20,
    0x7E: 0x01,
    0x7F: 0x6F,
    0x80: 0x5E,
    0x81: 0xC0,
    0x82: 0x53,
    0x83: 0x2B,
    0x84: 0x74,
    0x85: 0x2F,
    0x86: 0xBA,
    0x87: 0x8F,
    0x88: 0x19,
    0x89: 0x79,
    0x8A: 0x96,
    0x8B: 0xC5,
    0x8C: 0x5C,
    0x8D: 0x21,
    0x8E: 0x8C,
    0x8F: 0x02,
    0x90: 0x93,
    0x91: 0x15,
    0x92: 0x6A,
    0x93: 0xBF,
    0x94: 0xAA,
    0x95: 0x97,
    0x96: 0x3D,
    0x97: 0x49,
    0x98: 0x62,
    0x99: 0x6E,
    0x9B: 0xC3,
    0x9C: 0x60,
    0x9D: 0x4A,
    0x9E: 0x41,
    0x9F: 0x9C,
    0xA0: 0x73,
    0xA1: 0x54,
    0xA2: 0x68,
    0xA3: 0x37,
    0xA4: 0x43,
    0xA5: 0x82,
    0xA8: 0x85,
    0xA9: 0x58,
    0xAA: 0xA1,
    0xAB: 0x66,
    0xAC: 0xAC,
    0xAD: 0x50,
    0xAE: 0xB5,
    0xAF: 0x92,
    0xB0: 0x9E,
    0xB1: 0x1D,
    0xB2: 0x39,
    0xB3: 0xB2,
    0xB4: 0x7D,
    0xB5: 0xC1,
    0xB6: 0xC4,
    0xB7: 0x10,
    0xB8: 0xAE,
    0xB9: 0x9A,
    0xBA: 0x9D,
    0xBB: 0x1A,
    0xBC: 0x3B,
    0xBD: 0x86,
    0xBE: 0x45,
    0xBF: 0x8E,
    0xC0: 0x5A,
    0xC1: 0x88,
    0xC2: 0x5D,
    0xC3: 0x55,
    0xC4: 0x44,
    0xC5: 0x22,
    0xC6: 0x11,
    0xC7: 0xA0,
    0xC8: 0x31,
    0xC9: 0xB4,
    0xCA: 0xA4,
}

def decval(x,name):
 bits,ops=OPS[name];m=(1<<bits)-1
 for op,c in ops:
  if op==0:x^=c
  elif op==1:x=(x+c)&m
  elif op==2:x=(x-c)&m
  else:raise ValueError(op)
 return x&m
class Reader:
 def __init__(self,b):self.b=b;self.p=0
 def take(self,n):
  if self.p+n>len(self.b):raise ValueError(f'EOF p={self.p} n={n} len={len(self.b)}')
  x=self.b[self.p:self.p+n];self.p+=n;return x
 def u1(self):return self.take(1)[0]
 def u2(self):return int.from_bytes(self.take(2),'big')
 def u4(self):return int.from_bytes(self.take(4),'big')
class Writer:
 def __init__(self):self.b=bytearray()
 def put(self,x):self.b+=x
 def u1(self,x):self.b.append(x&255)
 def u2(self,x):self.b+=int(x).to_bytes(2,'big')
 def u4(self,x):self.b+=int(x).to_bytes(4,'big')

def decode_code_bytes(code):
 out=bytearray(code); p=0;n=len(code)
 while p<n:
  enc=code[p]
  if enc not in OPMAP:
   raise ValueError(f'unknown encoded opcode 0x{enc:02x} at code+0x{p:x}')
  op=OPMAP[enc];out[p]=op;start=p
  if op==0xaa:
   q=p+1
   while q%4:q+=1
   if q+12>n:raise ValueError('bad tableswitch')
   low=int.from_bytes(code[q+4:q+8],'big',signed=True);hi=int.from_bytes(code[q+8:q+12],'big',signed=True);cnt=hi-low+1
   if cnt<0 or cnt>100000:raise ValueError('bad tableswitch count')
   p=q+12+4*cnt
  elif op==0xab:
   q=p+1
   while q%4:q+=1
   if q+8>n:raise ValueError('bad lookupswitch')
   np=int.from_bytes(code[q+4:q+8],'big',signed=True)
   if np<0 or np>100000:raise ValueError('bad lookupswitch count')
   p=q+8+8*np
  elif op==0xc4:

   if p+2>n:raise ValueError('bad wide')
   sub_raw=code[p+1]

   valid=set(range(0x15,0x1a))|set(range(0x36,0x3b))|{0x84,0xa9}
   sub=sub_raw
   if sub not in valid and sub_raw in OPMAP and OPMAP[sub_raw] in valid:
    sub=OPMAP[sub_raw];out[p+1]=sub
   if sub not in valid:raise ValueError(f'bad wide sub 0x{sub_raw:02x}')
   p += 6 if sub==0x84 else 4
  else:
   lens={0x10:2,0x12:2,0x11:3,0x13:3,0x14:3,0x84:3,0xa9:2,0xbc:2,0xb9:5,0xba:5,0xc5:4,0xc8:5,0xc9:5}
   if 0x15<=op<=0x19 or 0x36<=op<=0x3a:l=2
   elif 0x99<=op<=0xa8 or 0xb2<=op<=0xb8 or op in (0xbb,0xbd,0xc0,0xc1,0xc6,0xc7):l=3
   else:l=lens.get(op,1)
   p+=l
  if p<=start or p>n:raise ValueError(f'bad instruction 0x{op:02x} at {start}, next {p}/{n}')
 return bytes(out)

def decode_protected(raw):
 stage=bytes(b^KEY[i%15] for i,b in enumerate(raw))
 if len(stage)<266:raise ValueError('too small')

 body=stage[256:]
 if body[:4]!=b'\xca\xfe\xba\xbe':raise ValueError(f'no CAFEBABE after signature: {body[:8].hex()}')
 r=Reader(body); w=Writer(); utf={}
 w.put(r.take(4))
 w.u2(decval(r.u2(),'minor'));w.u2(decval(r.u2(),'major'))
 cpc=decval(r.u2(),'cp_count');w.u2(cpc)
 i=1
 while i<cpc:
  rawtag=r.u1()
  if rawtag not in TAG:raise ValueError(f'unknown CP raw tag 0x{rawtag:02x} cp#{i} @0x{r.p-1:x}')
  tag=TAG[rawtag];w.u1(tag)
  if tag==1:
   ln=decval(r.u2(),'utf_len');w.u2(ln)
   bb=bytes(decval(x,'utf_byte') for x in r.take(ln));w.put(bb)
   try:utf[i]=bb.decode('utf8','replace')
   except:utf[i]=''
  elif tag==3:w.u4(decval(r.u4(),'int'))
  elif tag==4:w.u4(decval(r.u4(),'float'))
  elif tag==5:

   hi=decval(r.u4(),'long_high');lo=decval(r.u4(),'long_low');w.u4(hi);w.u4(lo);i+=1
  elif tag==6:
   hi=decval(r.u4(),'double_high');lo=decval(r.u4(),'double_low');w.u4(hi);w.u4(lo);i+=1
  elif tag==7:w.u2(decval(r.u2(),'class_idx'))
  elif tag==8:w.u2(decval(r.u2(),'string_idx'))
  elif tag==9:
   a=r.u2();b=r.u2();w.u2(decval(b,'fieldref_1'));w.u2(decval(a,'fieldref_2'))
  elif tag==10:
   a=r.u2();b=r.u2();w.u2(decval(b,'methodref_1'));w.u2(decval(a,'methodref_2'))
  elif tag==11:
   a=r.u2();b=r.u2();w.u2(decval(b,'imethodref_1'));w.u2(decval(a,'imethodref_2'))
  elif tag==12:
   a=r.u2();b=r.u2();w.u2(decval(b,'nat_1'));w.u2(decval(a,'nat_2'))
  elif tag==15:w.u1(decval(r.u1(),'mh_kind'));w.u2(decval(r.u2(),'mh_idx'))
  elif tag==16:w.u2(decval(r.u2(),'mtype_idx'))
  elif tag==18:
   a=r.u2();b=r.u2();w.u2(decval(b,'indy_1'));w.u2(decval(a,'indy_2'))
  else:raise ValueError(tag)
  i+=1

 ph=[r.u2() for _ in range(4)]
 access=decval(ph[2],'class_access');this=decval(ph[3],'class_this');superc=decval(ph[1],'class_super');ic=decval(ph[0],'class_icount')
 w.u2(access);w.u2(this);w.u2(superc);w.u2(ic)
 for _ in range(ic):w.u2(decval(r.u2(),'interface_idx'))
 fc=decval(r.u2(),'fields_count');w.u2(fc)
 for _ in range(fc):
  ph=[r.u2() for _ in range(4)]
  fa=decval(ph[1],'field_access');fn=decval(ph[3],'field_name');fd=decval(ph[0],'field_desc');ac=decval(ph[2],'field_acount')
  w.u2(fa);w.u2(fn);w.u2(fd);w.u2(ac)
  for __ in range(ac):decode_attr(r,w,utf,'field')
 mc=decval(r.u2(),'methods_count');w.u2(mc)
 for _ in range(mc):
  ph=[r.u2() for _ in range(4)]
  mn=decval(ph[0],'method_name');ac=decval(ph[1],'method_acount');ma=decval(ph[2],'method_access');md=decval(ph[3],'method_desc')
  w.u2(ma);w.u2(mn);w.u2(md);w.u2(ac)
  for __ in range(ac):decode_attr(r,w,utf,'method')
 cac=decval(r.u2(),'class_acount');w.u2(cac)
 for _ in range(cac):decode_attr(r,w,utf,'class')
 if r.p!=len(body):raise ValueError(f'trailing/underread {r.p}/{len(body)} diff={len(body)-r.p}')
 return bytes(w.b)

def decode_attr(r,w,utf,ctx):
 if ctx=='method':
  ln=decval(r.u4(),'method_attr_len');ni=decval(r.u2(),'method_attr_name')
 elif ctx=='field':
  ni=decval(r.u2(),'field_attr_name');ln=decval(r.u4(),'field_attr_len')
 elif ctx=='class':
  ni=decval(r.u2(),'class_attr_name');ln=decval(r.u4(),'class_attr_len')
 elif ctx=='code':
  ni=r.u2();ln=r.u4()
 else:raise ValueError(ctx)
 name=utf.get(ni,f'#{ni}')
 w.u2(ni);w.u4(ln)
 start=r.p
 if name=='Code':

  cl=decval(r.u4(),'code_len');ml=decval(r.u2(),'code_max_locals');ms=decval(r.u2(),'code_max_stack')
  if cl>ln:raise ValueError(f'bad code len {cl}/{ln}')
  w.u2(ms);w.u2(ml);w.u4(cl)
  code=r.take(cl);w.put(decode_code_bytes(code))

  rem=ln-(r.p-start)
  if rem<0:raise ValueError('negative Code rem')
  w.put(r.take(rem))
 else:

  w.put(r.take(ln))
 if r.p-start!=ln:raise ValueError(f'attr {name} length mismatch {r.p-start}/{ln}')

def _standard_class_name(b):
    def u2(p): return int.from_bytes(b[p:p+2], 'big')
    cp_count = u2(8)
    p = 10
    i = 1
    utf = {}
    cls = {}
    while i < cp_count:
        tag = b[p]; p += 1
        if tag == 1:
            ln = u2(p); p += 2
            utf[i] = b[p:p+ln].decode('utf-8', 'replace'); p += ln
        elif tag == 7:
            cls[i] = u2(p); p += 2
        elif tag in (8, 16, 19, 20): p += 2
        elif tag in (9, 10, 11, 12, 17, 18): p += 4
        elif tag in (3, 4): p += 4
        elif tag in (5, 6): p += 8; i += 1
        elif tag == 15: p += 3
        else: raise ValueError(f'bad standard constant-pool tag {tag} at #{i}')
        i += 1
    this_class = u2(p + 2)
    return utf[cls[this_class]]

def _looks_protected(raw):
    if len(raw) < 260:
        return False

    return bytes(raw[i] ^ KEY[i % len(KEY)] for i in range(256, 260)) == b'\xCA\xFE\xBA\xBE'

import io
import csv
import zipfile
import argparse

STRING_PROFILES = {
    "current": {
        "label": "current",
        "class": "rustme/liiiIIIiII",
        "method": "IiIIiilIll",
        "desc": "(Ljava/lang/String;)Ljava/lang/String;",
        "key": bytes.fromhex("096e62693d496b5e1f111f1c2d63146f"),
        "shift": 2,
    },
    "legacy": {
        "label": "legacy",
        "class": "rustme/lilIllllI",
        "method": "IIillIiIll",
        "desc": "(Ljava/lang/String;)Ljava/lang/String;",
        "key": bytes.fromhex("3a672612606f207248533d5c774d4708"),
        "shift": 1,
    },
}

STRING_KEY = b""
STRING_SHIFT = 0
STRING_DECRYPTOR_CLASS = None
STRING_DECRYPTOR_METHOD = None
STRING_DECRYPTOR_DESC = "(Ljava/lang/String;)Ljava/lang/String;"
ACTIVE_STRING_PROFILE = None

def _apply_string_profile(profile):
    global STRING_KEY, STRING_SHIFT, STRING_DECRYPTOR_CLASS
    global STRING_DECRYPTOR_METHOD, STRING_DECRYPTOR_DESC, ACTIVE_STRING_PROFILE
    ACTIVE_STRING_PROFILE = profile
    if profile is None:
        STRING_KEY = b""
        STRING_SHIFT = 0
        STRING_DECRYPTOR_CLASS = None
        STRING_DECRYPTOR_METHOD = None
        STRING_DECRYPTOR_DESC = "(Ljava/lang/String;)Ljava/lang/String;"
        return
    STRING_KEY = bytes(profile["key"])
    STRING_SHIFT = int(profile["shift"])
    STRING_DECRYPTOR_CLASS = profile["class"]
    STRING_DECRYPTOR_METHOD = profile["method"]
    STRING_DECRYPTOR_DESC = profile.get("desc", "(Ljava/lang/String;)Ljava/lang/String;")

def _profile_exists(classes_by_name, profile):
    ci = classes_by_name.get(profile["class"])
    if ci is None:
        return False
    for _, ni, di, _ in ci.methods:
        if ci.utf(int.from_bytes(ni, "big")) == profile["method"] and \
           ci.utf(int.from_bytes(di, "big")) == profile.get("desc", "(Ljava/lang/String;)Ljava/lang/String;"):
            return True
    return False

def select_string_profile(classes_by_name, requested="auto", custom=None):

    if custom is not None:
        return custom
    if requested == "off":
        return None
    if requested != "auto":
        profile = STRING_PROFILES.get(requested)
        if profile is None:
            raise ValueError("unknown string profile: %s" % requested)
        if not _profile_exists(classes_by_name, profile):
            raise ValueError(
                "requested string profile %r does not match this archive (%s.%s%s not found)" % (
                    requested, profile["class"], profile["method"], profile["desc"]
                )
            )
        return profile

    for name in ("current", "legacy"):
        profile = STRING_PROFILES[name]
        if _profile_exists(classes_by_name, profile):
            return profile
    return None

OP_EXTRA = {
    0x10:1, 0x11:2, 0x12:1, 0x13:2, 0x14:2,
    0x15:1, 0x16:1, 0x17:1, 0x18:1, 0x19:1,
    0x36:1, 0x37:1, 0x38:1, 0x39:1, 0x3A:1,
    0x84:2,
    0x99:2, 0x9A:2, 0x9B:2, 0x9C:2, 0x9D:2, 0x9E:2,
    0x9F:2, 0xA0:2, 0xA1:2, 0xA2:2, 0xA3:2, 0xA4:2,
    0xA5:2, 0xA6:2, 0xA7:2, 0xA8:2,
    0xB2:2, 0xB3:2, 0xB4:2, 0xB5:2, 0xB6:2, 0xB7:2, 0xB8:2,
    0xB9:4, 0xBA:4, 0xBB:2, 0xBC:1, 0xBD:2, 0xC0:2, 0xC1:2,
    0xC5:3, 0xC6:2, 0xC7:2,
}

def descriptor_stack_slots(desc):

    if not desc or not desc.startswith("("):
        return 0, 0
    i = 1
    slots = 0
    try:
        while i < len(desc) and desc[i] != ")":
            while desc[i] == "[":
                i += 1
            if desc[i] == "L":
                i = desc.index(";", i) + 1
                slots += 1
            else:
                slots += 2 if desc[i] in ("J", "D") else 1
                i += 1
        ret = desc[i + 1:] if i < len(desc) else "V"
    except (IndexError, ValueError):
        return 0, 0
    ret_slots = 0 if ret == "V" else (2 if ret[:1] in ("J", "D") else 1)
    return slots, ret_slots

def field_stack_slots(desc):
    return 2 if desc and desc[:1] in ("J", "D") else 1

def read_cp_standard(r, count):
    cp = [None] * count
    i = 1
    while i < count:
        b = r.read(1)
        if not b:
            raise ValueError("EOF in constant pool")
        t = b[0]
        if t == 1:
            n = int.from_bytes(r.read(2), "big")
            cp[i] = ("Utf8", r.read(n))
            i += 1
        elif t in (3, 4):
            cp[i] = ("num", r.read(4))
            i += 1
        elif t in (5, 6):
            cp[i] = ("num", r.read(8))
            i += 2
        elif t in (7, 19, 20):
            cp[i] = ("cls", int.from_bytes(r.read(2), "big"))
            i += 1
        elif t == 8:
            cp[i] = ("str", int.from_bytes(r.read(2), "big"))
            i += 1
        elif t in (9, 10, 11, 12, 17, 18):
            cp[i] = ("ref", r.read(4))
            i += 1
        elif t == 15:
            cp[i] = ("mh", r.read(3))
            i += 1
        elif t == 16:
            cp[i] = ("mt", r.read(2))
            i += 1
        else:
            raise ValueError("bad standard CP tag %d at #%d" % (t, i))
    return cp, r.tell()

def mutf8_units(data):
    out = []
    i = 0
    while i < len(data):
        b = data[i]
        if b < 0x80:
            out.append(b)
            i += 1
        elif (b & 0xE0) == 0xC0 and i + 1 < len(data):
            out.append(((b & 0x1F) << 6) | (data[i + 1] & 0x3F))
            i += 2
        elif (b & 0xF0) == 0xE0 and i + 2 < len(data):
            out.append(((b & 0x0F) << 12) |
                       ((data[i + 1] & 0x3F) << 6) |
                       (data[i + 2] & 0x3F))
            i += 3
        else:
            out.append(0xFFFD)
            i += 1
    return out

def mutf8_encode_units(units):
    out = bytearray()
    for c in units:
        c &= 0xFFFF
        if c == 0:
            out += b"\xC0\x80"
        elif c < 0x80:
            out.append(c)
        elif c < 0x800:
            out.append(0xC0 | (c >> 6))
            out.append(0x80 | (c & 0x3F))
        else:
            out.append(0xE0 | (c >> 12))
            out.append(0x80 | ((c >> 6) & 0x3F))
            out.append(0x80 | (c & 0x3F))
    return bytes(out)

def string_units_to_text(units):
    raw = b"".join(int(x & 0xFFFF).to_bytes(2, "big") for x in units)
    return raw.decode("utf-16-be", "surrogatepass")

def text_to_mutf8(text):
    raw = text.encode("utf-16-be", "surrogatepass")
    units = [int.from_bytes(raw[i:i+2], "big") for i in range(0, len(raw), 2)]
    return mutf8_encode_units(units)

def decrypt_string_utf8(raw_utf8):
    if not STRING_KEY or STRING_SHIFT <= 0:
        return None, None
    units = mutf8_units(raw_utf8)
    if not units:
        return b"", ""

    mask = (1 << STRING_SHIFT) - 1
    if not all((x & mask) == 0 for x in units):
        return None, None
    plain_units = [((x >> STRING_SHIFT) ^ STRING_KEY[i & 15]) & 0xFFFF
                   for i, x in enumerate(units)]
    return mutf8_encode_units(plain_units), string_units_to_text(plain_units)

class StdCls:
    __slots__ = (
        "raw", "name", "cp", "count", "cp_end", "r",
        "interfaces", "fields", "methods", "class_attrs",
        "code_patch", "utf8_new", "dec_utf8", "broken",
        "new_cp", "_new_utf8", "_new_str"
    )

    def __init__(self, raw):
        self.raw = raw
        self.code_patch = {}
        self.utf8_new = {}
        self.dec_utf8 = set()
        self.new_cp = []
        self._new_utf8 = {}
        self._new_str = {}
        self.broken = False
        self.name = None
        r = io.BytesIO(raw)
        if r.read(4) != b"\xCA\xFE\xBA\xBE":
            self.broken = True
            return
        r.read(4)
        count = int.from_bytes(r.read(2), "big")
        self.count = count
        try:
            self.cp, self.cp_end = read_cp_standard(r, count)
        except Exception:
            self.broken = True
            return
        self.r = r

    def utf(self, i):
        if not (0 < i < len(self.cp)):
            return None
        e = self.cp[i]
        if e and e[0] == "Utf8":
            try:
                return e[1].decode("latin1")
            except Exception:
                return None
        return None

    def utf_bytes(self, i):
        if not (0 < i < len(self.cp)):
            return None
        e = self.cp[i]
        return e[1] if e and e[0] == "Utf8" else None

    def next_idx(self):
        return self.count + len(self.new_cp)

    def find_utf8_ascii(self, text):
        target = text.encode("ascii")
        for i, e in enumerate(self.cp):
            if e and e[0] == "Utf8" and e[1] == target:
                return i
        for txt, idx in self._new_utf8.items():
            if txt == text:
                return idx
        return None

    def add_utf8_text(self, text):
        if text in self._new_utf8:
            return self._new_utf8[text]
        idx = self.next_idx()
        data = text_to_mutf8(text)
        self.new_cp.append((1, len(data).to_bytes(2, "big") + data))
        self._new_utf8[text] = idx
        return idx

    def find_str_for_utf8(self, utf8_idx):
        for i, e in enumerate(self.cp):
            if e and e[0] == "str" and e[1] == utf8_idx:
                return i
        return self._new_str.get(utf8_idx)

    def add_str(self, utf8_idx):
        if utf8_idx in self._new_str:
            return self._new_str[utf8_idx]
        idx = self.next_idx()
        self.new_cp.append((8, int(utf8_idx).to_bytes(2, "big")))
        self._new_str[utf8_idx] = idx
        return idx

    def resolve_ref(self, i):
        if not (0 < i < len(self.cp)):
            return None, None
        e = self.cp[i]
        if not e or e[0] != "ref":
            return None, None
        ci = int.from_bytes(e[1][0:2], "big")
        nti = int.from_bytes(e[1][2:4], "big")
        if not (0 < ci < len(self.cp) and 0 < nti < len(self.cp)):
            return None, None
        nt = self.cp[nti]
        if not nt or nt[0] != "ref":
            return None, None
        nc = int.from_bytes(nt[1][0:2], "big")
        nd = int.from_bytes(nt[1][2:4], "big")
        cls = self.cp[ci]
        if cls and cls[0] == "cls":
            cls = self.utf(cls[1])
        else:
            cls = None
        return cls, (self.utf(nc), self.utf(nd))

    def resolve_invokedynamic(self, i):
        if not (0 < i < len(self.cp)):
            return None, None
        e = self.cp[i]
        if not e or e[0] != "ref" or len(e[1]) != 4:
            return None, None
        nti = int.from_bytes(e[1][2:4], "big")
        if not (0 < nti < len(self.cp)):
            return None, None
        nt = self.cp[nti]
        if not nt or nt[0] != "ref" or len(nt[1]) != 4:
            return None, None
        nc = int.from_bytes(nt[1][0:2], "big")
        nd = int.from_bytes(nt[1][2:4], "big")
        return self.utf(nc), self.utf(nd)

def parse_std_structure(ci):
    r = ci.r
    r.seek(ci.cp_end)
    access = r.read(2)
    this_b = r.read(2)
    if len(this_b) != 2:
        return None
    this_i = int.from_bytes(this_b, "big")
    e = ci.cp[this_i] if 0 < this_i < len(ci.cp) else None
    if not e or e[0] != "cls":
        return None
    ci.name = ci.utf(e[1])
    r.read(2)
    ic = int.from_bytes(r.read(2), "big")
    ci.interfaces = r.read(ic * 2)

    fc = int.from_bytes(r.read(2), "big")
    fields = []
    for _ in range(fc):
        fa = r.read(2); ni = r.read(2); di = r.read(2)
        na = int.from_bytes(r.read(2), "big")
        attrs = []
        for __ in range(na):
            ani = int.from_bytes(r.read(2), "big")
            alen = int.from_bytes(r.read(4), "big")
            attrs.append((ani, r.read(alen)))
        fields.append((fa, ni, di, attrs))
    ci.fields = fields

    mc = int.from_bytes(r.read(2), "big")
    methods = []
    for _ in range(mc):
        ma = r.read(2); ni = r.read(2); di = r.read(2)
        na = int.from_bytes(r.read(2), "big")
        attrs = []
        for __ in range(na):
            ani = int.from_bytes(r.read(2), "big")
            alen = int.from_bytes(r.read(4), "big")
            attrs.append((ani, r.read(alen)))
        methods.append((ma, ni, di, attrs))
    ci.methods = methods

    na = int.from_bytes(r.read(2), "big")
    attrs = []
    for _ in range(na):
        ani = int.from_bytes(r.read(2), "big")
        alen = int.from_bytes(r.read(4), "big")
        attrs.append((ani, r.read(alen)))
    ci.class_attrs = attrs
    return True

def _source_str(owner, idx):
    return ("str", owner, int(idx))

def _unknown():
    return ("v", None)

def _pop_slots(stack, count):
    if count <= 0:
        return []
    if len(stack) < count:
        stack.clear()
        return None
    vals = stack[-count:]
    del stack[-count:]
    return vals

def _push_unknown(stack, count):
    stack.extend([_unknown()] * max(0, count))

def _fallback_stack_effect(op):

    table = {
        0x00:(0,0), 0x01:(0,1),
        0x02:(0,1),0x03:(0,1),0x04:(0,1),0x05:(0,1),0x06:(0,1),0x07:(0,1),0x08:(0,1),
        0x09:(0,2),0x0A:(0,2), 0x0B:(0,1),0x0C:(0,1),0x0D:(0,1), 0x0E:(0,2),0x0F:(0,2),
        0x2E:(2,1),0x2F:(2,2),0x30:(2,1),0x31:(2,2),0x32:(2,1),0x33:(2,1),0x34:(2,1),0x35:(2,1),
        0x4F:(3,0),0x50:(4,0),0x51:(3,0),0x52:(4,0),0x53:(3,0),0x54:(3,0),0x55:(3,0),0x56:(3,0),
        0x57:(1,0),0x58:(2,0),
        0x60:(2,1),0x61:(4,2),0x62:(2,1),0x63:(4,2),
        0x64:(2,1),0x65:(4,2),0x66:(2,1),0x67:(4,2),
        0x68:(2,1),0x69:(4,2),0x6A:(2,1),0x6B:(4,2),
        0x6C:(2,1),0x6D:(4,2),0x6E:(2,1),0x6F:(4,2),
        0x70:(2,1),0x71:(4,2),0x72:(2,1),0x73:(4,2),
        0x74:(1,1),0x75:(2,2),0x76:(1,1),0x77:(2,2),
        0x78:(2,1),0x79:(3,2),0x7A:(2,1),0x7B:(3,2),0x7C:(2,1),0x7D:(3,2),
        0x7E:(2,1),0x7F:(4,2),0x80:(2,1),0x81:(4,2),0x82:(2,1),0x83:(4,2),
        0x85:(1,2),0x86:(1,1),0x87:(1,2),0x88:(2,1),0x89:(2,1),0x8A:(2,2),
        0x8B:(1,1),0x8C:(1,2),0x8D:(1,2),0x8E:(2,1),0x8F:(2,2),0x90:(2,1),
        0x91:(1,1),0x92:(1,1),0x93:(1,1),0x94:(4,1),0x95:(2,1),0x96:(2,1),0x97:(4,1),0x98:(4,1),
        0x99:(1,0),0x9A:(1,0),0x9B:(1,0),0x9C:(1,0),0x9D:(1,0),0x9E:(1,0),
        0x9F:(2,0),0xA0:(2,0),0xA1:(2,0),0xA2:(2,0),0xA3:(2,0),0xA4:(2,0),0xA5:(2,0),0xA6:(2,0),
        0xA7:(0,0),0xA8:(0,1),0xA9:(0,0),0xAA:(1,0),0xAB:(1,0),
        0xAC:(1,0),0xAD:(2,0),0xAE:(1,0),0xAF:(2,0),0xB0:(1,0),0xB1:(0,0),
        0xBB:(0,1),0xBC:(1,1),0xBD:(1,1),0xBE:(1,1),0xBF:(1,0),
        0xC0:(1,1),0xC1:(1,1),0xC2:(1,0),0xC3:(1,0),0xC6:(1,0),0xC7:(1,0),
        0xC8:(0,0),0xC9:(0,1),0xCA:(0,0),
    }
    return table.get(op)

def walk_std_method(ci, code, assignments, sites, method_index):

    stack = []
    locals_ = {}
    pc = 0
    n = len(code)

    while pc < n:
        op = code[pc]
        extra = OP_EXTRA.get(op, 0)
        if extra and pc + extra + 1 > n:
            break

        if op == 0x12:
            idx = code[pc + 1]
            pc += 2
            if idx < len(ci.cp) and ci.cp[idx] and ci.cp[idx][0] == "str":
                stack.append(_source_str(ci.name, idx))
            else:
                _push_unknown(stack, 1)
            continue
        if op == 0x13:
            idx = int.from_bytes(code[pc + 1:pc + 3], "big")
            pc += 3
            if idx < len(ci.cp) and ci.cp[idx] and ci.cp[idx][0] == "str":
                stack.append(_source_str(ci.name, idx))
            else:
                _push_unknown(stack, 1)
            continue
        if op == 0x14:
            pc += 3
            _push_unknown(stack, 2)
            continue

        if 0x15 <= op <= 0x19:
            idx = code[pc + 1]
            pc += 2
            if op == 0x19:
                stack.append(locals_.get(idx, _unknown()))
            else:
                _push_unknown(stack, 2 if op in (0x16, 0x18) else 1)
            continue

        if 0x1A <= op <= 0x2D:
            if op <= 0x1D:
                _push_unknown(stack, 1)
            elif op <= 0x21:
                _push_unknown(stack, 2)
            elif op <= 0x25:
                _push_unknown(stack, 1)
            elif op <= 0x29:
                _push_unknown(stack, 2)
            else:
                idx = op - 0x2A
                stack.append(locals_.get(idx, _unknown()))
            pc += 1
            continue

        if 0x36 <= op <= 0x3A:
            idx = code[pc + 1]
            pc += 2
            width = 2 if op in (0x37, 0x39) else 1
            vals = _pop_slots(stack, width)
            if op == 0x3A and vals:
                locals_[idx] = vals[-1]
            else:
                locals_.pop(idx, None)
            continue

        if 0x3B <= op <= 0x4E:
            if op <= 0x3E:
                idx, width, is_ref = op - 0x3B, 1, False
            elif op <= 0x42:
                idx, width, is_ref = op - 0x3F, 2, False
            elif op <= 0x46:
                idx, width, is_ref = op - 0x43, 1, False
            elif op <= 0x4A:
                idx, width, is_ref = op - 0x47, 2, False
            else:
                idx, width, is_ref = op - 0x4B, 1, True
            vals = _pop_slots(stack, width)
            if is_ref and vals:
                locals_[idx] = vals[-1]
            else:
                locals_.pop(idx, None)
            pc += 1
            continue

        if op == 0x84:
            pc += 3
            continue

        if op == 0x59:
            if stack:
                stack.append(stack[-1])
            pc += 1
            continue
        if op == 0x5F:
            if len(stack) >= 2:
                stack[-1], stack[-2] = stack[-2], stack[-1]
            pc += 1
            continue
        if op in (0x5A,0x5B,0x5C,0x5D,0x5E):

            eff = {0x5A:(2,3),0x5B:(3,4),0x5C:(2,4),0x5D:(3,5),0x5E:(4,6)}[op]
            vals = _pop_slots(stack, eff[0])
            if vals is not None:
                _push_unknown(stack, eff[1])
            pc += 1
            continue

        if op in (0xB2, 0xB3, 0xB4, 0xB5):
            idx = int.from_bytes(code[pc + 1:pc + 3], "big")
            cls, rr = ci.resolve_ref(idx)
            pc += 3
            if rr is None:
                stack.clear()
                continue
            fn, fd = rr
            width = field_stack_slots(fd)
            if op == 0xB2:
                if width == 1 and fd == "Ljava/lang/String;":
                    stack.append(("field", cls, fn))
                else:
                    _push_unknown(stack, width)
            elif op == 0xB3:
                vals = _pop_slots(stack, width)
                if width == 1 and fd == "Ljava/lang/String;" and vals and cls and fn:
                    assignments.setdefault((cls, fn), []).append(vals[-1])
            elif op == 0xB4:
                _pop_slots(stack, 1)
                _push_unknown(stack, width)
            else:
                _pop_slots(stack, width)
                _pop_slots(stack, 1)
            continue

        if op in (0xB6, 0xB7, 0xB8, 0xB9):
            idx = int.from_bytes(code[pc + 1:pc + 3], "big")
            cls, rr = ci.resolve_ref(idx)
            call_pc = pc
            pc += 5 if op == 0xB9 else 3
            if rr is None:
                stack.clear()
                continue
            mn, md = rr
            arg_slots, ret_slots = descriptor_stack_slots(md)
            pops = arg_slots + (0 if op == 0xB8 else 1)
            popped = _pop_slots(stack, pops)
            if popped is None:
                continue
            is_dec = (
                op == 0xB8 and STRING_DECRYPTOR_CLASS is not None and
                cls == STRING_DECRYPTOR_CLASS and
                mn == STRING_DECRYPTOR_METHOD and md == STRING_DECRYPTOR_DESC and
                arg_slots == 1 and ret_slots == 1
            )
            if is_dec:
                src = popped[-1] if popped else _unknown()
                sites.append((method_index, call_pc, src))
                stack.append(("dec", src))
            else:
                _push_unknown(stack, ret_slots)
            continue

        if op == 0xBA:
            idx = int.from_bytes(code[pc + 1:pc + 3], "big")
            _, md = ci.resolve_invokedynamic(idx)
            pc += 5
            arg_slots, ret_slots = descriptor_stack_slots(md)
            _pop_slots(stack, arg_slots)
            _push_unknown(stack, ret_slots)
            continue

        if op in (0xAA, 0xAB):
            _pop_slots(stack, 1)
            q = pc + 1
            while q % 4:
                q += 1
            if op == 0xAA:
                if q + 12 > n:
                    break
                low = int.from_bytes(code[q+4:q+8], "big", signed=True)
                high = int.from_bytes(code[q+8:q+12], "big", signed=True)
                count = high - low + 1
                if count < 0 or count > 100000:
                    break
                pc = q + 12 + count * 4
            else:
                if q + 8 > n:
                    break
                np = int.from_bytes(code[q+4:q+8], "big", signed=True)
                if np < 0 or np > 100000:
                    break
                pc = q + 8 + np * 8
            continue

        if op == 0xC4:
            if pc + 4 > n:
                break
            sub = code[pc + 1]
            idx = int.from_bytes(code[pc+2:pc+4], "big")
            if sub in (0x15,0x16,0x17,0x18,0x19):
                if sub == 0x19:
                    stack.append(locals_.get(idx, _unknown()))
                else:
                    _push_unknown(stack, 2 if sub in (0x16,0x18) else 1)
                pc += 4
            elif sub in (0x36,0x37,0x38,0x39,0x3A):
                width = 2 if sub in (0x37,0x39) else 1
                vals = _pop_slots(stack, width)
                if sub == 0x3A and vals:
                    locals_[idx] = vals[-1]
                else:
                    locals_.pop(idx, None)
                pc += 4
            elif sub == 0x84:
                pc += 6
            elif sub == 0xA9:
                pc += 4
            else:
                break
            continue

        if op == 0xC5:
            if pc + 4 > n:
                break
            dims = code[pc + 3]
            _pop_slots(stack, dims)
            _push_unknown(stack, 1)
            pc += 4
            continue

        if op == 0xC0:
            pc += 3
            continue
        if op == 0xC1:
            _pop_slots(stack, 1)
            _push_unknown(stack, 1)
            pc += 3
            continue

        eff = _fallback_stack_effect(op)
        if eff is None:

            break
        pops, pushes = eff
        _pop_slots(stack, pops)
        _push_unknown(stack, pushes)

        if op in (0x99,0x9A,0x9B,0x9C,0x9D,0x9E,0x9F,0xA0,0xA1,0xA2,0xA3,0xA4,0xA5,0xA6,0xA7,0xA8,0xC6,0xC7):
            pc += 3
        elif op in (0x10,0xBC):
            pc += 2
        elif op in (0x11,0xBB,0xBD):
            pc += 3
        elif op in (0xC8,0xC9):
            pc += 5
        else:
            pc += 1

def skip_element_value(av, pos):
    if pos >= len(av):
        return None
    tag = av[pos]
    pos += 1
    if tag in (66,67,68,70,73,74,83,90,115):
        return pos + 2
    if tag == 101:
        return pos + 4
    if tag == 99:
        return pos + 2
    if tag == 64:
        return skip_annotation(av, pos)
    if tag == 91:
        if pos + 2 > len(av):
            return None
        n = int.from_bytes(av[pos:pos+2], "big")
        pos += 2
        for _ in range(n):
            pos = skip_element_value(av, pos)
            if pos is None:
                return None
        return pos
    return None

def skip_annotation(av, pos):
    if pos + 4 > len(av):
        return None
    n = int.from_bytes(av[pos + 2:pos + 4], "big")
    pos += 4
    for _ in range(n):
        if pos + 2 > len(av):
            return None
        pos += 2
        pos = skip_element_value(av, pos)
        if pos is None:
            return None
    return pos

def filter_annotations(ci, av, drop):
    if len(av) < 2:
        return None
    n = int.from_bytes(av[0:2], "big")
    out = bytearray()
    pos = 2
    kept = 0
    for _ in range(n):
        if pos + 2 > len(av):
            return None
        type_i = int.from_bytes(av[pos:pos+2], "big")
        end = skip_annotation(av, pos)
        if end is None or end > len(av):
            return av
        if ci.utf(type_i) not in drop:
            out += av[pos:end]
            kept += 1
        pos = end
    if pos != len(av):
        return av
    if kept == 0:
        return None
    return kept.to_bytes(2, "big") + bytes(out)

def filter_parameter_annotations(ci, av, drop):
    if not av:
        return None
    pcount = av[0]
    pos = 1
    out = bytearray([pcount])
    any_ann = False

    for _ in range(pcount):
        if pos + 2 > len(av):
            return av
        n = int.from_bytes(av[pos:pos+2], "big")
        pos += 2
        kept = []
        for __ in range(n):
            if pos + 2 > len(av):
                return av
            type_i = int.from_bytes(av[pos:pos+2], "big")
            end = skip_annotation(av, pos)
            if end is None:
                return av
            if ci.utf(type_i) not in drop:
                kept.append(av[pos:end])
                any_ann = True
            pos = end
        out += len(kept).to_bytes(2, "big")
        for item in kept:
            out += item

    return bytes(out) if any_ann else None

def strip_char_annotations(ci):
    changed = False
    ann_attrs = {"RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"}
    param_attrs = {
        "RuntimeVisibleParameterAnnotations",
        "RuntimeInvisibleParameterAnnotations",
    }

    def patch_attr_list(attrs):
        nonlocal changed
        for i in range(len(attrs) - 1, -1, -1):
            ani, av = attrs[i]
            name = ci.utf(ani)
            if name in ann_attrs:
                new_av = filter_annotations(ci, av, {"C"})
            elif name in param_attrs:
                new_av = filter_parameter_annotations(ci, av, {"C"})
            else:
                continue

            if new_av is None:
                del attrs[i]
                changed = True
            elif new_av != av:
                attrs[i] = (ani, new_av)
                changed = True

    patch_attr_list(ci.class_attrs)
    for _,_,_,attrs in ci.fields:
        patch_attr_list(attrs)
    for _,_,_,attrs in ci.methods:
        patch_attr_list(attrs)
    return changed

def resolve_leaf_refs(src, assignments, seen=None):
    if seen is None:
        seen = set()
    kind = src[0]

    if kind == "str":
        return {(src[1], src[2])}

    if kind == "dec":
        return resolve_leaf_refs(src[1], assignments, seen)

    if kind == "field":
        key = (src[1], src[2])
        if key in seen:
            return None
        vals = assignments.get(key)
        if not vals:
            return None
        seen = set(seen)
        seen.add(key)
        out = set()
        for v in vals:
            leaves = resolve_leaf_refs(v, assignments, seen)
            if leaves is None:
                return None
            out |= leaves
        return out

    return None

def decrypt_leaf(classes_by_name, leaf, plain_cache):
    if leaf in plain_cache:
        return plain_cache[leaf] is not None

    owner, cp_idx = leaf
    ci = classes_by_name.get(owner)
    if ci is None or not (0 < cp_idx < len(ci.cp)):
        plain_cache[leaf] = None
        return False

    e = ci.cp[cp_idx]
    if not e or e[0] != "str":
        plain_cache[leaf] = None
        return False
    uidx = e[1]
    if not (0 < uidx < len(ci.cp)):
        plain_cache[leaf] = None
        return False
    u = ci.cp[uidx]
    if not u or u[0] != "Utf8":
        plain_cache[leaf] = None
        return False

    if uidx in ci.dec_utf8:

        new_raw = ci.utf8_new.get(uidx, u[1])
        try:
            txt = string_units_to_text(mutf8_units(new_raw))
        except Exception:
            txt = None
        plain_cache[leaf] = txt
        return True

    new_raw, text = decrypt_string_utf8(u[1])
    if new_raw is None:
        plain_cache[leaf] = None
        return False

    ci.dec_utf8.add(uidx)
    ci.utf8_new[uidx] = new_raw
    plain_cache[leaf] = text
    return True

def code_bytes_from_attr(av):
    cr = io.BytesIO(av)
    header = cr.read(4)
    if len(header) != 4:
        return None
    clen_b = cr.read(4)
    if len(clen_b) != 4:
        return None
    clen = int.from_bytes(clen_b, "big")
    code = cr.read(clen)
    if len(code) != clen:
        return None
    return code

def patch_string_calls(ci, class_sites, patchable):
    for mi, (_,_,_,attrs) in enumerate(ci.methods):
        pcs = {pc for m, pc, _ in class_sites if m == mi and (ci.name, mi, pc) in patchable}
        if not pcs:
            continue
        for ani, av in attrs:
            if ci.utf(ani) != "Code":
                continue
            cr = io.BytesIO(av)
            cr.read(4)
            clen = int.from_bytes(cr.read(4), "big")
            code = bytearray(cr.read(clen))
            changed = False
            for pc in pcs:
                if 0 <= pc and pc + 3 <= len(code) and code[pc] == 0xB8:
                    code[pc:pc+3] = b"\x00\x00\x00"
                    changed = True
            if changed:
                ci.code_patch[mi] = bytes(code)

def inline_unique_static_strings(classes_by_name, assignments, plain_cache):
    inlined = 0

    for (target_cls, target_field), srcs in assignments.items():
        ci = classes_by_name.get(target_cls)
        if ci is None:
            continue

        all_text = set()
        okay = True
        for src in srcs:
            leaves = resolve_leaf_refs(src, assignments)
            if not leaves:
                okay = False
                break
            for leaf in leaves:

                if leaf not in plain_cache or plain_cache.get(leaf) is None:
                    okay = False
                    break
                all_text.add(plain_cache[leaf])
            if not okay:
                break

        if not okay or len(all_text) != 1:
            continue
        value = next(iter(all_text))

        for fi, (fa, ni_b, di_b, attrs) in enumerate(ci.fields):
            access = int.from_bytes(fa, "big")
            ni = int.from_bytes(ni_b, "big")
            di = int.from_bytes(di_b, "big")
            if ci.utf(ni) != target_field:
                continue
            if ci.utf(di) != "Ljava/lang/String;":
                continue
            if not (access & 0x0008):
                continue
            if any(ci.utf(ai) == "ConstantValue" for ai, _ in attrs):
                continue

            ui = ci.add_utf8_text(value)
            si = ci.add_str(ui)
            cvi = ci.find_utf8_ascii("ConstantValue")
            if cvi is None:
                cvi = ci.add_utf8_text("ConstantValue")
            attrs.append((cvi, int(si).to_bytes(2, "big")))
            inlined += 1
            break

    return inlined

def rewrite_std_class(ci):
    r = ci.r
    r.seek(0)
    out = bytearray(r.read(8))
    out += (ci.count + len(ci.new_cp)).to_bytes(2, "big")

    raw_cp = ci.raw[10:ci.cp_end]
    pos = 0
    i = 1
    while i < ci.count:
        tag = raw_cp[pos]
        out.append(tag)
        pos += 1

        if tag == 1:
            n = int.from_bytes(raw_cp[pos:pos+2], "big")
            pos += 2
            if i in ci.utf8_new:
                data = ci.utf8_new[i]
                out += len(data).to_bytes(2, "big") + data
            else:
                out += n.to_bytes(2, "big") + raw_cp[pos:pos+n]
            pos += n
            i += 1
        elif tag in (3,4):
            out += raw_cp[pos:pos+4]; pos += 4; i += 1
        elif tag in (5,6):
            out += raw_cp[pos:pos+8]; pos += 8; i += 2
        elif tag in (7,8,16,19,20):
            out += raw_cp[pos:pos+2]; pos += 2; i += 1
        elif tag in (9,10,11,12,17,18):
            out += raw_cp[pos:pos+4]; pos += 4; i += 1
        elif tag == 15:
            out += raw_cp[pos:pos+3]; pos += 3; i += 1
        else:
            raise ValueError("bad CP tag while rewriting: %d" % tag)

    for tag, payload in ci.new_cp:
        out.append(tag)
        out += payload

    r.seek(ci.cp_end)
    out += r.read(2)
    out += r.read(2)
    out += r.read(2)
    icb = r.read(2)
    ic = int.from_bytes(icb, "big")
    out += icb
    out += r.read(ic * 2)

    out += len(ci.fields).to_bytes(2, "big")
    for fa, ni, di, attrs in ci.fields:
        out += fa + ni + di + len(attrs).to_bytes(2, "big")
        for ani, av in attrs:
            out += int(ani).to_bytes(2, "big")
            out += len(av).to_bytes(4, "big")
            out += av

    out += len(ci.methods).to_bytes(2, "big")
    for mi, (ma, ni, di, attrs) in enumerate(ci.methods):
        out += ma + ni + di + len(attrs).to_bytes(2, "big")
        for ani, av in attrs:
            if ci.utf(ani) == "Code" and mi in ci.code_patch:
                patched = ci.code_patch[mi]
                old_clen = int.from_bytes(av[4:8], "big")
                new_av = av[:8] + patched + av[8 + old_clen:]
                out += int(ani).to_bytes(2, "big")
                out += len(new_av).to_bytes(4, "big")
                out += new_av
            else:
                out += int(ani).to_bytes(2, "big")
                out += len(av).to_bytes(4, "big")
                out += av

    out += len(ci.class_attrs).to_bytes(2, "big")
    for ani, av in ci.class_attrs:
        out += int(ani).to_bytes(2, "big")
        out += len(av).to_bytes(4, "big")
        out += av

    return bytes(out)

def prepare_classes_from_archive(input_path, preserve_resources=True):
    entries = []
    mapping = []
    stats = {
        "protected_decoded": 0,
        "standard_classes": 0,
        "resources": 0,
        "decode_failures": 0,
    }

    with zipfile.ZipFile(input_path, "r") as zin:
        for zi in zin.infolist():
            if zi.is_dir():
                continue
            raw = zin.read(zi)

            if _looks_protected(raw):
                try:
                    clean = decode_protected(raw)
                    real_name = _standard_class_name(clean) + ".class"
                    entries.append((real_name, clean, True))
                    mapping.append((zi.filename, real_name, len(raw), len(clean)))
                    stats["protected_decoded"] += 1
                except Exception as e:
                    stats["decode_failures"] += 1
                    if preserve_resources:
                        entries.append((zi.filename, raw, False))

            elif raw.startswith(b"\xCA\xFE\xBA\xBE"):
                try:
                    real_name = _standard_class_name(raw) + ".class"
                except Exception:
                    real_name = zi.filename
                entries.append((real_name, raw, True))
                mapping.append((zi.filename, real_name, len(raw), len(raw)))
                stats["standard_classes"] += 1

            else:
                stats["resources"] += 1
                if preserve_resources:
                    entries.append((zi.filename, raw, False))

    return entries, mapping, stats

def full_deobfuscate_entries(entries, aggressive_strings=False, inline_fields=False, string_profile="auto", custom_profile=None):
    classes_by_name = {}
    entry_to_ci = {}
    assignments = {}
    sites_by_class = {}
    stats = {
        "class_parse_failures": 0,
        "char_classes_changed": 0,
        "decryptor_calls_found": 0,
        "decryptor_calls_patched": 0,
        "strings_decrypted": 0,
        "fields_inlined": 0,
        "aggressive_strings": 0,
    }

    for entry_name, raw, is_class in entries:
        if not is_class:
            continue
        ci = StdCls(raw)
        if ci.broken or not parse_std_structure(ci) or not ci.name:
            stats["class_parse_failures"] += 1
            continue
        classes_by_name[ci.name] = ci
        entry_to_ci[entry_name] = ci

    selected_profile = select_string_profile(classes_by_name, string_profile, custom_profile)
    _apply_string_profile(selected_profile)
    stats["string_profile"] = selected_profile.get("label", "custom") if selected_profile else "none"

    for ci in classes_by_name.values():
        class_sites = []
        for mi, (_,_,_,attrs) in enumerate(ci.methods):
            for ani, av in attrs:
                if ci.utf(ani) != "Code":
                    continue
                code = code_bytes_from_attr(av)
                if code is not None:
                    walk_std_method(ci, code, assignments, class_sites, mi)
        if class_sites:
            sites_by_class[ci.name] = class_sites
            stats["decryptor_calls_found"] += len(class_sites)

    plain_cache = {}
    patchable = set()

    for cls_name, class_sites in sites_by_class.items():
        for mi, pc, src in class_sites:
            leaves = resolve_leaf_refs(src, assignments)
            if not leaves:
                continue
            ok = True
            for leaf in leaves:
                if not decrypt_leaf(classes_by_name, leaf, plain_cache):
                    ok = False
                    break
            if ok:
                patchable.add((cls_name, mi, pc))

    stats["decryptor_calls_patched"] = len(patchable)

    if aggressive_strings and ACTIVE_STRING_PROFILE is not None:
        for ci in classes_by_name.values():
            for e in ci.cp:
                if not e or e[0] != "str":
                    continue
                uidx = e[1]
                if uidx in ci.dec_utf8:
                    continue
                u = ci.cp[uidx] if 0 < uidx < len(ci.cp) else None
                if not u or u[0] != "Utf8":
                    continue
                new_raw, text = decrypt_string_utf8(u[1])
                if new_raw is None:
                    continue

                units = mutf8_units(new_raw)
                if not units:
                    continue
                printable = sum(
                    x in (9,10,13) or 32 <= x < 127 or 0xA0 <= x < 0xD800 or 0xE000 <= x < 0xFFFE
                    for x in units
                ) / len(units)
                if printable < 0.90:
                    continue
                ci.dec_utf8.add(uidx)
                ci.utf8_new[uidx] = new_raw
                stats["aggressive_strings"] += 1

    stats["strings_decrypted"] = sum(len(ci.dec_utf8) for ci in classes_by_name.values())

    for ci in classes_by_name.values():
        class_sites = sites_by_class.get(ci.name, [])
        if class_sites:
            patch_string_calls(ci, class_sites, patchable)
        if strip_char_annotations(ci):
            stats["char_classes_changed"] += 1

    if inline_fields:
        stats["fields_inlined"] = inline_unique_static_strings(
            classes_by_name, assignments, plain_cache
        )

    rebuilt = {}
    for entry_name, raw, is_class in entries:
        if not is_class:
            rebuilt[entry_name] = raw
            continue
        ci = entry_to_ci.get(entry_name)
        rebuilt[entry_name] = rewrite_std_class(ci) if ci else raw

    return rebuilt, stats, classes_by_name

def count_remaining_decryptor_calls(classes_by_name):
    total = 0
    external = 0
    if STRING_DECRYPTOR_CLASS is None:
        return total, external
    for ci in classes_by_name.values():
        for mi, (_,_,_,attrs) in enumerate(ci.methods):
            for ani, av in attrs:
                if ci.utf(ani) != "Code":
                    continue
                code = ci.code_patch.get(mi)
                if code is None:
                    code = code_bytes_from_attr(av)
                if code is None:
                    continue

                pc = 0
                n = len(code)
                while pc < n:
                    op = code[pc]
                    if op == 0xB8 and pc + 3 <= n:
                        idx = int.from_bytes(code[pc+1:pc+3], "big")
                        cls, rr = ci.resolve_ref(idx)
                        if rr:
                            mn, md = rr
                            if (
                                cls == STRING_DECRYPTOR_CLASS and
                                mn == STRING_DECRYPTOR_METHOD and
                                md == STRING_DECRYPTOR_DESC
                            ):
                                total += 1
                                if ci.name != STRING_DECRYPTOR_CLASS:
                                    external += 1

                    if op == 0xAA:
                        q = pc + 1
                        while q % 4:
                            q += 1
                        if q + 12 > n:
                            break
                        low = int.from_bytes(code[q+4:q+8], "big", signed=True)
                        high = int.from_bytes(code[q+8:q+12], "big", signed=True)
                        cnt = high - low + 1
                        if cnt < 0 or cnt > 100000:
                            break
                        pc = q + 12 + 4 * cnt
                    elif op == 0xAB:
                        q = pc + 1
                        while q % 4:
                            q += 1
                        if q + 8 > n:
                            break
                        np = int.from_bytes(code[q+4:q+8], "big", signed=True)
                        if np < 0 or np > 100000:
                            break
                        pc = q + 8 + 8 * np
                    elif op == 0xC4:
                        if pc + 2 > n:
                            break
                        pc += 6 if code[pc+1] == 0x84 else 4
                    else:
                        pc += 1 + OP_EXTRA.get(op, 0)

    return total, external

def write_output_jar(output_path, rebuilt):
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        for name, raw in rebuilt.items():
            zout.writestr(name, raw)

def full_process(input_path, output_path, mapping_path=None,
                 preserve_resources=True, aggressive_strings=False,
                 inline_fields=False, string_profile="auto", custom_profile=None,
                 strict=True):
    entries, mapping, prep_stats = prepare_classes_from_archive(
        input_path, preserve_resources=preserve_resources
    )
    rebuilt, deobf_stats, parsed = full_deobfuscate_entries(
        entries,
        aggressive_strings=aggressive_strings,
        inline_fields=inline_fields,
        string_profile=string_profile,
        custom_profile=custom_profile,
    )
    write_output_jar(output_path, rebuilt)

    final_classes = {}
    for name, raw in rebuilt.items():
        if not raw.startswith(b"\xCA\xFE\xBA\xBE"):
            continue
        ci = StdCls(raw)
        if not ci.broken and parse_std_structure(ci) and ci.name:
            final_classes[ci.name] = ci
    remaining_total, remaining_external = count_remaining_decryptor_calls(final_classes)

    if mapping_path:
        with open(mapping_path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["original_entry", "clean_class_path", "encrypted_size", "clean_size"])
            w.writerows(mapping)

    stats = {}
    stats.update(prep_stats)
    stats.update(deobf_stats)
    stats["remaining_decryptor_calls_total"] = remaining_total
    stats["remaining_decryptor_calls_external"] = remaining_external
    expected_final_classes = sum(1 for _, raw in rebuilt.items() if raw.startswith(b"\xCA\xFE\xBA\xBE"))
    stats["final_class_parse_failures"] = max(0, expected_final_classes - len(final_classes))

    if strict:
        problems = []
        if stats.get("decode_failures"):
            problems.append("%d protected-class decode failure(s)" % stats["decode_failures"])
        if stats.get("class_parse_failures"):
            problems.append("%d input class parse failure(s)" % stats["class_parse_failures"])
        if stats.get("final_class_parse_failures"):
            problems.append("%d rebuilt class parse failure(s)" % stats["final_class_parse_failures"])
        if stats.get("remaining_decryptor_calls_external"):
            problems.append("%d external decryptor call(s) remain" % stats["remaining_decryptor_calls_external"])
        if problems:
            raise RuntimeError("strict validation failed: " + "; ".join(problems))
    return stats

def _default_out(inp):
    return inp.with_name(inp.stem + "_FULL_DEOBF.jar")

def _parse_hex_key(text):
    try:
        key = bytes.fromhex(text)
    except ValueError as e:
        raise argparse.ArgumentTypeError("invalid hex key: %s" % e)
    if len(key) != 16:
        raise argparse.ArgumentTypeError("string key must be exactly 16 bytes (32 hex chars)")
    return key

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output", nargs="?")
    ap.add_argument("--mapping")
    ap.add_argument("--classes-only", action="store_true")
    ap.add_argument("--aggressive-strings", action="store_true")
    ap.add_argument("--inline-fields", action="store_true")
    ap.add_argument("--string-profile", choices=["auto", "current", "legacy", "off"], default="auto")
    ap.add_argument("--decryptor-class")
    ap.add_argument("--decryptor-method")
    ap.add_argument("--string-key", type=_parse_hex_key)
    ap.add_argument("--string-shift", type=int, choices=range(1, 16), metavar="N")
    ap.add_argument("--allow-partial", action="store_true")
    args = ap.parse_args()

    inp = Path(args.input)
    if not inp.exists():
        raise SystemExit("input not found: %s" % inp)
    if inp.suffix.lower() not in (".jar", ".zip"):
        raise SystemExit("input must be .jar or .zip")

    custom_items = (args.decryptor_class, args.decryptor_method, args.string_key, args.string_shift)
    custom_profile = None
    if any(x is not None for x in custom_items):
        if not all(x is not None for x in custom_items):
            raise SystemExit("custom cipher options incomplete")
        custom_profile = {
            "label": "custom",
            "class": args.decryptor_class.replace(".", "/"),
            "method": args.decryptor_method,
            "desc": "(Ljava/lang/String;)Ljava/lang/String;",
            "key": args.string_key,
            "shift": args.string_shift,
        }

    out = Path(args.output) if args.output else _default_out(inp)
    mapping = Path(args.mapping) if args.mapping else out.with_suffix(".mapping.csv")

    try:
        stats = full_process(
            inp, out, mapping,
            preserve_resources=not args.classes_only,
            aggressive_strings=args.aggressive_strings,
            inline_fields=args.inline_fields,
            string_profile=args.string_profile,
            custom_profile=custom_profile,
            strict=not args.allow_partial,
        )
    except (zipfile.BadZipFile, ValueError, RuntimeError) as e:
        raise SystemExit(str(e))

    print("decoded=%d" % stats["protected_decoded"])
    print("standard=%d" % stats["standard_classes"])
    print("decode_failures=%d" % stats["decode_failures"])
    print("parse_failures=%d" % stats["class_parse_failures"])
    print("final_parse_failures=%d" % stats["final_class_parse_failures"])
    print("profile=%s" % stats.get("string_profile", "none"))
    print("char_cleaned=%d" % stats["char_classes_changed"])
    print("decrypt_calls=%d" % stats["decryptor_calls_found"])
    print("decrypt_patched=%d" % stats["decryptor_calls_patched"])
    print("strings=%d" % stats["strings_decrypted"])
    print("fields_inlined=%d" % stats["fields_inlined"])
    if args.aggressive_strings:
        print("aggressive_strings=%d" % stats["aggressive_strings"])
    print("remaining=%d" % stats["remaining_decryptor_calls_total"])
    print("remaining_external=%d" % stats["remaining_decryptor_calls_external"])
    print("jar=%s" % out)
    print("mapping=%s" % mapping)

if __name__ == "__main__":
    main()
