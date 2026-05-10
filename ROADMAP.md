感谢您的补充指正，已根据反馈对设计文档做出如下修订：

1. **纹理获取逻辑**：明确优先从 `.minecraft/assets/indexes/` 的映射文件读取资源，再从对应 `objects/` 获取实际文件；加载模组时则从模组 jar 中提取。
2. **存档选择**：移除预设路径扫描，改为由用户通过系统文件选择器（SAF）或自建文件管理器主动导航到目标存档文件夹。
3. **快捷栏功能**：增加“从物品栏获取”“手动编写 NBT”“从世界中间获取方块”等交互。
4. **删除版本计划**：附录中的版本规划全部移除。

以下是更新后的 ZaralynChisel 设计文档完整版本。

---

# ZaralynChisel 设计文档（修订版）

## 1. 项目概述

### 1.1 项目名称
**ZaralynChisel** —— Android 端 Minecraft Java 版存档可视化编辑器。

### 1.2 项目定位
外置编辑器，无需运行 Minecraft 即可对世界进行高效编辑与管理。

### 1.3 核心目标
- **God 模式**（地图概览式编辑）与 **Player 模式**（类游戏内操作）双模式。
- 支持区块和方块的批量操作（删除、加载、复制/剪切/粘贴、空置域）。
- 纹理获取优先级：本地 assets 索引及模组 jar 优先，网络下载作为补充。
- Material Design 3 风格，支持多点触控和键鼠。
- 高性能渲染，空闲时不运行完整游戏引擎。

### 1.4 目标用户
- 地图建造者、服务器管理员、数据包/模组开发者、普通冒险玩家。


## 2. 技术选型与依赖

| 领域 | 选型 | 说明 |
|------|------|------|
| 平台 | 原生 Android（Kotlin/Java） | 最佳性能和底层能力 |
| 区块/NBT解析 | Hephaistos（MIT, Kotlin） | 专为 Anvil 格式设计，若长期无维护则 fork 自维护 |
| 异步任务 | Kotlin Coroutines + Android Executors | 结构化并发 |
| 撤销/重做 | AndroidX UndoManager + Regret | 支持多种对象类型 |
| 渲染（Player 模式） | 自研简化体素引擎 + OpenGL ES 3.0 | 自行控制区块网格和视野加载 |
| 纹理解析 | 自研资源提取器 | 支持 assets 索引、jar 遍历、网络下载 |
| 文件访问 | 自建文件管理器（SAF + 自定义文件选择器） | 由用户手动选择存档位置 |
| 导入/导出结构 | 支持 `.schematic` / `.mcstructure` | 基于 NBT |

> **库维护要求**：禁止使用超过 7 个月未更新的库。若开发中 Hephaistos 仍无更新，则 fork 并自行维护 ARM32/ARM64 兼容性。

### 2.1 渲染引擎建议
- 基于 OpenGL ES 3.0 编写简易渲染器，实现智能区块网格生成（仅可见面）、面剔除及平滑光照。
- 参考 Sodium 的现代渲染思路优化批处理。
- 空区块显示为虚空（透明或背景色），不渲染任何几何体。


## 3. 系统架构

```
ZaralynChisel
├── EditionCore
│   ├── AnvilReader/Writer
│   ├── NBT Editor
│   ├── BlockBatchProcessor
│   └── HistoryManager
├── RenderEngine
│   ├── GodMap
│   ├── PlayerRenderer
│   ├── ChunkMeshBuilder
│   └── TextureResolver
├── FileAccessLayer
│   ├── WorldSelector（用户手动选择存档）
│   ├── AssetsExtractor（解析 .minecraft/assets/indexes 和 objects）
│   ├── ModsJarExtractor（遍历 jar 提取纹理）
│   └── NetworkFetcher
├── UI（Material Design 3）
│   ├── GodModeActivity
│   ├── PlayerModeActivity
│   ├── FilePickerActivity
│   └── SettingsFragment
├── Utils
│   ├── PermissionHelper（root / Shizuku / SAF 降级）
│   └── AsyncTaskQueue
```

## 4. 核心模块详细设计

### 4.1 区块编辑器（EditionCore）
- **删除区块**：支持半径、矩形+圆形选区，修改 `.mca` 或标记 chunk 删除。
- **加载区块**：强制读取指定 chunk。
- **复制/剪切/粘贴**：支持跨存档。粘贴时可设置坐标偏移。
- **空置域**：将选区所有非空气方块替换为空气。
- **撤销/重做**：批量修改前保存原 NBT，支持最多 50 步可调。

### 4.2 纹理获取（TextureResolver）
**优先级顺序**：
1. 当前打开存档所在 Minecraft 目录的 `.minecraft/assets/indexes/<version>.json`（映射文件）→ `objects/` 下对应 hash 文件。
2. 加载的模组 jar（支持 Forge、Fabric、NeoForge）内的 `assets/<modid>/textures/block/`。
3. 网络下载：从预设镜像（可配置）下载对应版本默认纹理，存入本地缓存。
4. 若全部失败，**拒绝进入 Player 模式**（弹窗提示，回退至 God 模式）。

- **缓存**：网络下载的纹理放入内建缓存目录，用户可手动清除或设置大小上限（如 512 MB，LRU 淘汰）。
- 缺失且下载失败的纹理在 Player 模式中用棋盘格占位符替代，但需禁用修改操作以避免视觉混乱。

### 4.3 文件访问层
- **不预设任何路径**。用户首次使用时需通过系统文件选择器（Android Storage Access Framework）或自建文件浏览器**主动导航**到目标存档目录（例如 `/sdcard/FCL/.minecraft/saves/MyWorld` 或 `/sdcard/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/xxx`）。
- 归档后，软件记录该路径，下次启动可直接打开。
- 支持 root 和 Shizuku 辅助提升权限(默认关闭)，但最低保证使用 SAF 访问用户选择的文件夹。
- Android 10 及以上若需访问 `/Android/data/` 下的存档，引导用户使用 SAF 、 root/Shizuku 或者使用零宽空格漏洞访问(需用户同意)。

### 4.4 Player 模式操作细节
- **基础操作**：放置/破坏方块、与容器交互、编辑方块 NBT（长按方块呼出编辑器）。
- **快捷栏设计**：
  - 独立快捷栏（9 格）与存档内玩家物品栏隔离。
  - 用户可从 **存档内玩家物品栏** 中取物品到快捷栏。
  - 支持 **手动编写 NBT** 生成任意自定义方块。
  - 支持 **中间获取方块**（类似原版挑取键）：在 Player 模式下，点击或瞄准某个方块后，将其放入快捷栏（无需破坏）。
- **移动与碰撞**：默认开启，可关闭碰撞检测；支持键鼠模式（自动识别外设）。
- **区块加载**：仅加载视野内区块，超出后卸载。空区块不显示任何内容。
- **操作后处理**：放置/破坏后自动重新计算光照和高度图。

### 4.5 选区与可视化
- **选区类型**：矩形（轴对齐）和圆形（圆形投影为圆柱形范围）。
- **可视化**：
  - God 模式下用半透明矩形/圆形覆盖。
  - Player 模式下可类似 F3+G 显示区块网格，选区边界用粒子或线框轮廓提示。
- **批量操作**：支持限制单次处理区块数量（可调），多线程分批处理，显示“操作中...”后台通知。

## 5. UI/UX 设计（Material Design 3）

### 5.1 双模式设计
- **God 模式**：
  - 2D 区块地图，缩放/拖动。
  - 工具栏：删除/加载/复制/粘贴/空置域。
  - 选区生成（矩形+圆形拖拽）。
  - 区块网格线可开关。
- **Player 模式**：
  - 第一人称触屏操作 / 自动切换键鼠。
  - 显示 FPS、坐标、区块坐标。
  - 长按方块打开 NBT 编辑器。
  - 区块网格显示（类似 F3+G）。

### 5.2 通用规范
- 预设浅色/深色/自动三种主题，多种强调色可选。
- 支持多点触控旋转/缩放（Player 模式下触屏旋转视角）。
- 长耗时操作显示“操作中...”通知，支持后台继续并推送完成提醒。

### 5.3 撤销/重做
- 所有修改支持撤销/重做，历史面板显示最近 50 条操作。
- 连续相同类型操作（如连续放置方块）可智能合并为一个历史条目。

### 5.4 跨存档与多开
- 同时打开多个存档（标签页切换）。
- 跨存档复制区块：复制 → 切换存档 → 粘贴（可设置偏移）。

## 6. 性能与错误处理

### 6.1 内存与多线程
- 用户可设置最大内存（默认 Runtime 上限的 70%）。
- 批量处理区块时分批（默认一次 16 区块，可调）。
- 使用 Coroutine 执行并行任务，主线程仅 UI 更新。

### 6.2 渲染优化（参考 Sodium）
- 仅渲染面向玩家的可见面，剔除背后区块。
- 纹理图集打包减少绑定调用。
- 平滑光照可选开关，适配低端设备。
- 最大渲染距离可调（最高 48 区块）。

### 6.3 错误处理
- 损坏的 chunk 跳过并记录日志（导出到 `/sdcard/ZaralynChisel/logs`）。
- 网络下载纹理失败后，显示棋盘格占位符并提示用户自行添加资源包。
- Player 模式若所有纹理缺失则强制降级 God 模式。
