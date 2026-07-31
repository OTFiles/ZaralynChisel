# 区块加载流程

> 中文伪代码说明，格式 `步骤 | 文件::函数` — 描述实际执行的操作。

---

## 阶段 1：世界扫描 (应用启动时)

```
入口: GodModeScreen::LaunchedEffect(worldPath)

├─ WorldSelector::loadWorldInfo(worldPath)
│  └─ 读取 level.dat → 解析 Player.Pos / Spawn / DataVersion
│     → 决定视角中心点 (player chunk > spawn > newest terrain centroid)
│
├─ WorldCache::get(worldPath)    ← 缓存命中则跳过扫描
│  └─ 过期? → 返回 null, 触发重新扫描
│
├─ WorldSelector::scanChunks(dimensionPath) × 2  (主世界 + 下界)
│  │
│  │  FOR EACH region_file IN region/*.mca:
│  │  │
│  │  ├─  SAF.openInputStream(relPath)  或  FileInputStream
│  │  ├─  AnvilReader::fromStream(stream)
│  │  │  └─ 把整个 .mca 流复制到临时文件 (region_XXXX.tmp)
│  │  │     → RandomAccessFile(临时文件)   ← 瓶颈① 每个 region 都全文复制
│  │  │
│  │  ├─  AnvilReader::open()
│  │  │  └─ 读 header 前 8192 字节 (location table 4KiB + timestamp table 4KiB)
│  │  │     → 存入内存 byte[] (不是每次 seek 去读)
│  │  │
│  │  FOR lx = 0..31, lz = 0..31:    ← 1024 个格子
│  │  │  AnvilReader::readChunkHeader(lx, lz)
│  │  │  ├─ 从内存 header 读 location 条目: sectorOffset(3B) + sectorCount(1B)
│  │  │  ├─ 从内存 header 读 timestamp 条目 (4B)
│  │  │  └─ IF sectorOffset > 0 AND sectorCount > 0
│  │  │     → 记录 ChunkInfo(x, z, dimension, timestamp, sectorCount)
│  │  │
│  │  └─ reader.close() → tempFile.delete()
│  │
│  └─ 返回 List<ChunkInfo>  (主世界约 31k, 下界约 4k)
│
├─ WorldCache::put(worldPath, (worldData, chunkList))
│  └─ 内存缓存, 下次启动跳过扫描 (WorldCache 是一个 HashMap<String, Pair>)
│
└─ chunks = chunkList   (mutableStateOf, 触发 Compose 重组)
   中心定位: centerOnGeneratedTerrain(chunks, worldData)
```

## 阶段 2：地表加载 (后台持续轮询)

```
入口: GodModeScreen::LaunchedEffect(worldPath, worldData)

WHILE coroutine.isActive:

  ├─ 计算视口窗口
  │  viewRadius = clamp(200 / zoom, 10, 500)
  │  minX = floor(viewX) - viewRadius
  │  maxX = floor(viewX) + viewRadius
  │  (minZ, maxZ 同理)
  │
  ├─ 筛选待加载区块
  │  visible = chunks
  │    .filter { dimension == currentDim
  │           && x in minX..maxX && z in minZ..maxZ
  │           && key NOT IN surfaceCache    ← 已加载地表
  │           && key NOT IN emptyCache }    ← 已知为全空 (无需重试)
  │    .sortedBy { dist² = (x-viewX)² + (z-viewZ)² }   ← 离视角最近的先加载
  │    .take(30)                            ← 每批 30 个 (瓶颈②)
  │
  ├─ IF visible 为空 → delay(150ms); continue
  │                     ↑ 闲置等待 (瓶颈③)
  │
  └─ FOR EACH chunk IN visible:       ← 串行逐个加载 (瓶颈④)
     │
     └─ WorldSelector::loadChunkSurface(worldPath, chunkX, chunkZ, dim)
        │
        ├─ 计算 region 坐标
        │  regionX = chunkX >> 5
        │  regionZ = chunkZ >> 5
        │  regionFile = worldPath/dim/region/r.regionX.regionZ.mca
        │
        ├─ IF 缓存的 region reader 对应此 region → 复用 (跳过流复制)
        │  ELSE:
        │  │  SAF.openInputStream(relPath) 或 FileInputStream
        │  │  → AnvilReader::fromStream(stream)
        │  │     └─ 把整个 .mca 流复制到临时文件    ← 瓶颈⑤ 每次换 region 都全文复制
        │  │  → reader.open() 读 header
        │  └─ 缓存此 reader (cachedRegionIdx = regionKey)
        │
        ├─ lx = chunkX & 31, lz = chunkZ & 31
        │
        ├─ reader.readChunkHeader(lx, lz)
        │  └─ IF sectorOffset == 0 → 返回 null (该区块在磁盘上不存在)
        │
        ├─ reader.readChunkData(lx, lz)
        │  ├─ seek(sectorOffset × 4096)
        │  ├─ 读 4 字节 → chunkLength (Big-Endian)
        │  ├─ 读 1 字节 → compressionType (1=GZip, 2=Zlib, 3=未压缩)
        │  ├─ 读 (chunkLength-1) 字节 → compressedNBT  ← 减去压缩类型字节
        │  └─ 返回 compressedNBT (不含压缩类型字节的纯压缩数据)
        │
        └─ ChunkSurfaceReader::readSurface(compressedNBT, dim)
           │
           ├─ 1) Zlib 解压 → NBT 字节流                ← 瓶颈⑥ 每个区块解压一次
           ├─ 2) NbtReader 解析 TAG_Compound 根标签
           ├─ 3) 读 Heightmaps::MOTION_BLOCKING (TAG_Long_Array)
           │     decodeHeightmap: 每 9 bit 解码一个表面高度 (共 256 列)
           │     → storedHeight + worldMinY - 1 = 绝对 Y
           │       (worldMinY: 主世界 1.18+ = -64, 下界 = 0)
           │
           ├─ 4) 读 sections[] 列表 (24 个 section, Y:-4 到 19)
           │     FOR EACH column (z*16+x):
           │       找到 surfaceY 对应的 section
           │       → 查此 section 的 block_states.palette
           │         → 找到 surface block 的 block name
           │         → MapColorPalette 查表 → MapColor ID (0-63)
           │         → 存入 result[z*16+x]
           │       IF 所在 column 全是空气 → result[i] = 0
           │
           └─ 返回 result[256]  (z*16+x 索引)
```

### 地表数据缓存

```
surfaceCache: SnapshotStateMap<Long, ChunkInfo>
  key   = chunkKey(dim, x, z) = (dim.ordinal << 48) | (x << 24) | z
  value = ChunkInfo 的副本, 带 surfaceColors: IntArray? (256 个 MapColor ID)

emptyCache: SnapshotStateMap<Long, Unit>
  记录已确认全空的区块 (无需重复尝试加载)

rendering:
  surfaceCache.values  → 筛选 dimension == currentDim
  → GodMapRenderer.render(width, height, chunks, config)
  → 每个 chunk 画一个 16×16px 的 Bitmap (每个像素 = MapColor → ARGB)
```

## 阶段 3：写入操作 (粘贴/剪切/删除)

```
入口: GodModeScreen 工具栏按钮 onClick

粘贴 (Paste):
  ├─ 计算粘贴原点:
  │  clipCx = (clip.chunks.minX + clip.chunks.maxX) / 2   ← 剪贴板中心
  │  originX = floor(viewX) + (clip.originChunkX - clipCx)
  │  (originZ 同理)
  │
  ├─ BlockBatchProcessor::pasteChunks(dim, originX, originZ, clipboard)
  │  ├─ offset = (originX - clip.originChunkX, originZ - clip.originChunkZ)
  │  ├─ FOR EACH src_chunk: 目标 = src + offset
  │  │  → 按目标 region 分组
  │  └─ FOR EACH region:
  │     │  AnvilWriter(regionFile).open()   ← RandomAccessFile(regionFile, "rw")
  │     │  FOR EACH (lx, lz, compressedNBT):
  │     │  │  findFreeSector(sectorsNeeded)
  │     │  │  ├─ 读取完整 location table
  │     │  │  ├─ 寻找已删除 sector 留下的空隙 (优先复用)
  │     │  │  └─ 无空隙 → 追加到文件末尾
  │     │  │
  │     │  │  写入: [4B length=1+data.size][1B compressionType=2][data][padding]
  │     │  │  更新 location table & timestamp
  │     │  └─ writer.close()
  │     └─ emit(BatchProgress...)
  │
  ├─ invalidateRange(dim, minX, minZ, maxX, maxZ)
  │  ├─ FOR EACH chunk in range:
  │  │  surfaceCache.remove(key)
  │  │  emptyCache.remove(key)
  │  │  IF chunk NOT IN chunks list → 添加 stub ChunkInfo        ← 新区块
  │  └─ worldSelector.clearReaderCache()                         ← 清 reader 缓存
  │
  └─ reloadFootprint(dim, minX, minZ, maxX, maxZ)    ← 立即重载,不等轮询
     └─ FOR EACH chunk: loadChunkSurface → surfaceCache 或 emptyCache

删除 (Delete):
  ├─ BlockBatchProcessor::deleteChunks(dim, selection)
  │  └─ AnvilWriter::deleteChunk(lx, lz)
  │     └─ 清零 location table 条目 (4 字节 → 0x00000000)
  │        旧 sector 不回收 → findFreeSector 扫描时发现空隙就会复用
  ├─ invalidateRange
  └─ reloadFootprint → loadChunkSurface 返回 null → emptyCache

剪切 (Cut) = 复制 + 删除, 其余同上
```

## 性能瓶颈分析

| # | 位置 | 描述 | 影响 |
|---|------|------|------|
| ① | `AnvilReader.fromStream` | 每次打开 region 都把整个 .mca 复制到临时文件 | 阶段1扫描: 每个 region 文件 (最大 ~8MB) 全文复制; 31k chunk = 约 28 个 region → 200+MB 磁盘 I/O |
| ② | `take(30)` | 每批只加载 30 个区块 | 31k chunk 约需 1000 批 → 每个 delay(50) = 约 50 秒加载全部 |
| ③ | `delay(150)` | 空闲时轮询间隔长 | 写操作后要等 150ms 才开始加载新区块 (v0.4.0 已用 reloadFootprint 绕过) |
| ④ | 串行 `for` 循环 | 批内逐个加载 | 30 个区块逐个解压 NBT, 没有并行 |
| ⑤ | `fromStream` 全文复制 | 见① | 阶段2 切换 region 时同样触发: 每换一个 region 就复制一次 |
| ⑥ | Zlib + NBT 解析 | 每个区块解压并解析完整 NBT | 31k 个 full chunk 各 24 sections, 每个 ~12KB 压缩 → 共约 360MB zlib 解压 |

### 优化方向

- **并行加载**: 将 `for (chunk in visible)` 改为 `coroutineScope { launch { ... } }` 并发 N 个
- **避免全文复制**: 不让 `fromStream` 复制整个 region → 改用 SAF 的 `ParcelFileDescriptor` 直接 seek, 或通过 `RegionFileCache` 引用已复制的临时文件
- **增量 NBT 解析**: 不解析完整 NBT, 而是只提取 Heightmaps + sections 中涉及 surface 列的那些 block; 可参考 mcasaenk 的 `GetValueFromBitArray`
- **缓存 region header**: 已缓存 (`cachedReader`), 但 `clearReaderCache` 在写入后清掉 — 可以改为只清 location table 中受影响的部分
