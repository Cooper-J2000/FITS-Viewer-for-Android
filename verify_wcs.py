#!/usr/bin/env python3
"""交叉验证 Kotlin Wcs.kt 中实现的 TAN/SIN 投影公式（用 Python 复刻同一套公式，与 astropy 对比）。
依赖：numpy、astropy。运行：python3 verify_wcs.py"""
import numpy as np
from math import radians, degrees, sin, cos, tan, atan2, asin, acos, sqrt, pi
from astropy.wcs import WCS

# ---- 复刻 Kotlin Wcs.kt 的实现 ----
class KWcs:
    def __init__(self, crval1, crval2, crpix1, crpix2, cd, proj):
        self.crval1, self.crval2 = crval1, crval2
        self.crpix1, self.crpix2 = crpix1, crpix2
        self.cd = cd
        det = cd[0]*cd[3] - cd[1]*cd[2]
        self.cdinv = [cd[3]/det, -cd[1]/det, -cd[2]/det, cd[0]/det]
        self.proj = proj

    def native_to_sky(self, phi, theta):
        phip = pi
        dp = radians(self.crval2)
        dphi = phi - phip
        dec = asin(sin(theta)*sin(dp) + cos(theta)*cos(dp)*cos(dphi))
        ra = radians(self.crval1) + atan2(-cos(theta)*sin(dphi),
                                          sin(theta)*cos(dp) - cos(theta)*sin(dp)*cos(dphi))
        return degrees(ra) % 360, degrees(dec)

    def sky_to_native(self, ra, dec):
        phip = pi
        dp = radians(self.crval2)
        a = radians(ra) - radians(self.crval1)
        d = radians(dec)
        theta = asin(sin(d)*sin(dp) + cos(d)*cos(dp)*cos(a))
        phi = phip + atan2(-cos(d)*sin(a), sin(d)*cos(dp) - cos(d)*sin(dp)*cos(a))
        return phi, theta

    def pix2world(self, x, y):  # 0-based
        dx = (x+1.0) - self.crpix1
        dy = (y+1.0) - self.crpix2
        ix = self.cd[0]*dx + self.cd[1]*dy
        iy = self.cd[2]*dx + self.cd[3]*dy
        if self.proj == 'TAN':
            r = sqrt(ix*ix + iy*iy)
            theta = atan2(180/pi, r)
            phi = atan2(radians(ix), -radians(iy))
            return self.native_to_sky(phi, theta)
        elif self.proj == 'SIN':
            r = sqrt(ix*ix + iy*iy)
            theta = acos(max(-1, min(1, radians(r))))
            phi = atan2(radians(ix), -radians(iy))
            return self.native_to_sky(phi, theta)

    def world2pix(self, ra, dec):
        phi, theta = self.sky_to_native(ra, dec)
        if self.proj == 'TAN':
            r = (180/pi)/tan(theta)
        else:
            r = degrees(cos(theta))
        ix = r*sin(phi); iy = -r*cos(phi)
        dx = self.cdinv[0]*ix + self.cdinv[1]*iy
        dy = self.cdinv[2]*ix + self.cdinv[3]*iy
        return self.crpix1 + dx - 1.0, self.crpix2 + dy - 1.0

def check(proj, crval1, crval2, cd_matrix, label):
    w = WCS(naxis=2)
    w.wcs.ctype = [f'RA---{proj}', f'DEC--{proj}']
    w.wcs.crval = [crval1, crval2]
    w.wcs.crpix = [512.0, 512.0]
    w.wcs.cd = np.array(cd_matrix).reshape(2, 2)
    k = KWcs(crval1, crval2, 512.0, 512.0,
             [cd_matrix[0], cd_matrix[1], cd_matrix[2], cd_matrix[3]], proj)
    maxerr_sky = 0; maxerr_pix = 0
    for (px, py) in [(0,0), (100,900), (511,511), (1023,0), (300,700), (800,200)]:
        ra_a, dec_a = w.wcs_pix2world([[px, py]], 0)[0]
        ra_k, dec_k = k.pix2world(px, py)
        err = max(abs((ra_a-ra_k+180)%360-180)*3600*cos(radians(dec_a)), abs(dec_a-dec_k)*3600)
        maxerr_sky = max(maxerr_sky, err)
        # 逆变换
        bx, by = k.world2pix(ra_a, dec_a)
        maxerr_pix = max(maxerr_pix, abs(bx-px), abs(by-py))
    print(f'{label:30s} 正向最大误差 = {maxerr_sky:.2e} 角秒, 逆向最大误差 = {maxerr_pix:.2e} 像素')

s = 0.0003  # ~1"/pix
check('TAN', 150.1, 2.2,   [-s, 0, 0, s], 'TAN 标准 (北上东左)')
check('TAN', 202.47, 47.2, [-s*0.7, s*0.714, s*0.714, s*0.7], 'TAN 旋转45.6度')
check('TAN', 10.0, -70.0,  [s, 0, 0, s], 'TAN 南天翻转')
check('TAN', 359.9, 0.05,  [-s, 0, 0, s], 'TAN RA跨0度')
check('SIN', 83.6, 22.0,   [-s, 0, 0, s], 'SIN 标准')
check('SIN', 266.4, -29.0, [-s*0.9, s*0.1, s*0.1, s*0.9], 'SIN 微旋转')

# 六十进制解析验证
def parse_sexa(s_, is_ra):
    parts = s_.strip().split(':')
    if len(parts) < 2:
        return float(s_)
    sign = -1.0 if parts[0].strip().startswith('-') else 1.0
    a = float(parts[0].lstrip('+-')); b = float(parts[1]); c = float(parts[2]) if len(parts) > 2 else 0
    v = sign*(a + b/60 + c/3600)
    return v*15 if is_ra else v

from astropy.coordinates import SkyCoord
c = SkyCoord('13:29:52.7 +47:11:43', unit=('hourangle', 'deg'))
ra_p = parse_sexa('13:29:52.7', True); dec_p = parse_sexa('+47:11:43', False)
print(f'六十进制: 误差=({abs(c.ra.deg-ra_p)*3600:.2e}", {abs(c.dec.deg-dec_p)*3600:.2e}")')
c2 = SkyCoord('05:34:31.9 -05:27:10', unit=('hourangle', 'deg'))
ra_p2 = parse_sexa('05:34:31.9', True); dec_p2 = parse_sexa('-05:27:10', False)
print(f'六十进制(负Dec): 误差=({abs(c2.ra.deg-ra_p2)*3600:.2e}", {abs(c2.dec.deg-dec_p2)*3600:.2e}")')
