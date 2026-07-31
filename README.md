# ZaralynChisel

Minecraft 存档的轻量级地图查看与区块编辑器 (Android).

## 功能

- **上帝模式 (2D)**: 自顶向下的区块地图，渲染 MapColor 地表颜色
- **3D 模式**: 展示区块地形 Mesh (GLES 3.0)
- **区块操作**: 选取 → 删除/复制/剪切/粘贴，蓝色预览显示粘贴落点
- 支持主世界 & 下界 (dimension)
- 支持 SAF (Storage Access Framework) / 直接文件访问

## 构建

```bash
./gradlew assembleDebug
```

最低 SDK: 26 (Android 8.0), 目标 SDK: 35 (Android 15).

## 架构

```
app/src/main/java/com/zaralynchisel/
├── editioncore/          # 区块读写核心
│   ├── AnvilReader.kt    # 读 .mca (header + 区块数据 + 地表解码)
│   ├── AnvilWriter.kt    # 写 .mca (sector 分配 + 区块写入)
│   ├── NbtReader.kt      # 流式 NBT 解析器
│   ├── BlockBatchProcessor.kt  # 批量操作 (复制/粘贴/删除)
│   └── WorldData.kt      # 数据模型 (ChunkInfo, SelectionArea, WorldData)
├── fileaccess/           # 文件访问层
│   ├── WorldSelector.kt  # 世界验证 + 区块扫描 + 地表加载
│   └── WorldCache.kt     # 内存缓存 (扫描结果)
├── renderengine/         # 渲染引擎
│   ├── GodMapRenderer.kt # 2D 上帝模式 Canvas 渲染
│   ├── ChunkSurfaceReader.kt  # 区块 NBT → MapColor 地表颜色解码
│   ├── MapColorPalette.kt     # Minecraft MapColor LUT
│   └── PlayerRenderer.kt      # 3D 地形 Mesh (GLES 3.0)
└── ui/                   # Composable 界面
    ├── MainActivity.kt
    ├── godmode/GodModeScreen.kt   # 上帝模式主界面 + surface loader
    └── playermode/PlayerModeScreen.kt
```

## 文档

- [区块加载流程](docs/chunk-loading.md) — 中文伪代码，详细说明扫描→加载→渲染的全链路
