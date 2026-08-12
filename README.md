# FITS Viewer — 安卓 FITS 文件查看器

参考 NASA fv 与 SAO ds9 功能设计的原生 Android 应用（Kotlin，无第三方 FITS 依赖，
解析器按 FITS Standard 4.0 自行实现）。

## 功能

| 模块 | 功能 |
|---|---|
| HDU 浏览 | 打开 .fits/.fit/.fts，列出全部扩展层（Primary/Image/BinTable/ASCII Table）及维度 |
| Header | 完整 80 字符卡片查看，关键词/值/注释 实时搜索过滤 |
| 图像 | BITPIX 8/16/32/64/-32/-64，BSCALE/BZERO/BLANK；捏合缩放、双击放大、平移；Linear/Log/Sqrt/Asinh 拉伸；亮度/对比度滑条；Gray/Gray-Inv/Heat/Viridis 色表；高斯平滑 σ=1/2；大图自动降采样防 OOM |
| WCS | 读取 CD/PC+CDELT/CDELT+CROTA2 矩阵，TAN/SIN 投影；触摸实时显示像素值与 RA/Dec（六十进制）；一键"北上东左"（由 WCS 数值求北/东方向向量，自动旋转+翻转，任何投影均适用） |
| Region | 加载标准 ds9 .reg（image/physical/fk5/icrs 坐标系，circle/ellipse/box/point/line/text/annulus，支持六十进制位置与 "/'/d 尺寸单位、color/text 属性）；长按创建圆形区域；保存为标准 .reg（有 WCS 输出 fk5，否则 image） |
| 表格 | BINTABLE（L X B I J K A E D C M P/Q，含 TSCAL/TZERO、矢量列、变长数组标记）与 ASCII TABLE（TBCOL + Aw/Iw/Fw.d/Ew/Dw）；显示列名/单位/行号 |
| 绘图 | 按列（X=行号或任意数值列，Y=数值列）或按行（该行全部数值列）绘制折线图/散点图/柱状图，轴标签带单位 |

## 构建

1. 用 **Android Studio**（Hedgehog 2023.1+ 即可）打开本仓库目录；
2. 首次打开会自动下载 Gradle 与依赖（需联网），等待 Sync 完成；
3. 菜单 `Build → Build Bundle(s)/APK(s) → Build APK(s)`，产物在
   `app/build/outputs/apk/debug/app-debug.apk`；
4. 或命令行（已配置 ANDROID_HOME 时）：`./gradlew assembleDebug`。

- minSdk 24 (Android 7.0)，targetSdk 34。
- 无需任何存储权限（通过系统文件选择器 SAF 访问文件）。
- 文件管理器中可用"打开方式 → FITS Viewer"直接打开 FITS 文件。

## 代码结构

```
app/src/main/java/com/fitsviewer/app/
├── fits/     FitsHeader.kt  卡片/Header 解析
│             FitsFile.kt    2880B 块 HDU 扫描、图像读取
│             FitsTable.kt   BINTABLE / ASCII TABLE 按需读取
├── wcs/      Wcs.kt         线性矩阵 + TAN/SIN 投影双向变换
├── region/   Ds9Region.kt   ds9 .reg 解析与序列化
├── render/   ImageRenderer.kt 拉伸/色表/亮度对比度/高斯平滑
├── view/     FitsImageView.kt 手势缩放/北上东左/region叠加/坐标读出
│             ChartView.kt   折线/散点/柱状图
└── *Activity.kt             Main(HDU列表) / Header / Image / Table / Chart
```

## 验证

`verify_wcs.py`：用 Python 复刻 Wcs.kt 的同一套公式与 astropy 交叉对比，
TAN/SIN 各姿态（旋转、南天、RA 跨 0°）正向误差 ~1e-10 角秒，逆向 <0.003 像素。

## 已知简化

- NAXIS>2 的数据立方体只显示第一个切面；
- 变长数组列 (P/Q) 只显示元素个数，不展开；
- galactic/ecliptic 坐标系的 region 会跳过（计数提示）；
- 天球坐标 region 的 box/ellipse 角度按图像坐标角近似（无 WCS 旋转补偿）。
