# EQ-LAB 아이콘 생성기: 크기마다 다른 도안(작을수록 단순·굵게)을 그려 app.ico 하나로 묶는다.
# 실행: python3 windows/tools/make_icon.py  (Pillow 필요)
import io, struct, os
from PIL import Image, ImageDraw

BG=(11,17,18,255); EDGE=(43,65,67,255); MINT=(95,227,200,255); WHITE=(232,244,242,255)
BAR1=(31,111,98,255); BAR2=(42,156,136,255)
SS=8

def bez(p0,p1,p2,p3,n=60):
    pts=[]
    for i in range(n+1):
        t=i/n; u=1-t
        pts.append((u**3*p0[0]+3*u*u*t*p1[0]+3*u*t*t*p2[0]+t**3*p3[0],
                    u**3*p0[1]+3*u*u*t*p1[1]+3*u*t*t*p2[1]+t**3*p3[1]))
    return pts

def curve(y_base,y_top):
    a=bez((10,y_base),(28,y_base),(32,y_top),(50,y_top))
    b=bez((50,y_top),(68,y_top),(72,y_base),(90,y_base))
    return a+b[1:]

def line(d,pts,w,col):
    d.line(pts,fill=col,width=int(w),joint="curve")
    r=w/2
    for x,y in (pts[0],pts[-1]):
        d.ellipse((x-r,y-r,x+r,y+r),fill=col)

def render(size):
    S=size*SS; k=S/100.0
    im=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(im)
    rad=20*k
    d.rounded_rectangle((0,0,S-1,S-1),radius=rad,fill=BG)
    if size>=24:
        d.rounded_rectangle((0.5*k,0.5*k,S-1-0.5*k,S-1-0.5*k),radius=rad,outline=EDGE,width=max(1,int(1.2*k)))
    sc=lambda pts:[(x*k,y*k) for x,y in pts]
    if size>=48:
        xs=[14,25,36,47,58,69,80]; hs=[24,34,44,50,38,28,18]; ws=7
        for x,h,c in zip(xs,hs,[BAR1,BAR1,BAR2,BAR2,BAR2,BAR1,BAR1]):
            d.rounded_rectangle(((x)*k,(80-h)*k,(x+ws)*k,80*k),radius=3*k,fill=c)
        line(d,sc(curve(62,24)),5*k,MINT)
        c=(50*k,24*k); r=6*k; d.ellipse((c[0]-r,c[1]-r,c[0]+r,c[1]+r),fill=WHITE)
    elif size>=32:
        for x,h in ((16,30),(42,46),(68,24)):
            d.rounded_rectangle((x*k,(80-h)*k,(x+12)*k,80*k),radius=4*k,fill=BAR2)
        line(d,sc(curve(64,24)),9*k,MINT)
        c=(50*k,24*k); r=8*k; d.ellipse((c[0]-r,c[1]-r,c[0]+r,c[1]+r),fill=WHITE)
    elif size>=24:
        line(d,sc(curve(68,26)),12*k,MINT)
        c=(50*k,26*k); r=9*k; d.ellipse((c[0]-r,c[1]-r,c[0]+r,c[1]+r),fill=WHITE)
    else:
        line(d,sc(curve(70,28)),17*k,MINT)
    return im.resize((size,size),Image.LANCZOS)

sizes=[256,128,64,48,32,24,20,16]
png=[]
for s in sizes:
    b=io.BytesIO(); render(s).save(b,"PNG"); png.append((s,b.getvalue()))
out=struct.pack("<HHH",0,1,len(png)); off=6+16*len(png); body=b""
for s,data in png:
    out+=struct.pack("<BBBBHHII",0 if s>=256 else s,0 if s>=256 else s,0,0,1,32,len(data),off+len(body)); body+=data
here=os.path.dirname(os.path.abspath(__file__))
open(os.path.join(here,"..","EqLabWin","app.ico"),"wb").write(out+body)
# 미리보기(확대)
sheet=Image.new("RGBA",(sum(s*(1 if s>=64 else 4) for s in sizes[2:])+40,300),(40,40,40,255))
x=10
for s in sizes[2:]:
    f=1 if s>=64 else 4; im=render(s).resize((s*f,s*f),Image.NEAREST); sheet.paste(im,(x,10),im); x+=s*f+8
sheet.save(os.path.join(here,"icon_preview.png"))
