<p align="center">
  <img src="img/woodworking_table_render.png" alt="WoodworkingSaw Logo" width="128">
</p>

<h1 align="center">锯木台 WoodworkingSaw</h1>

<p align="center">集原木去皮与木板切割加工于一体的锯木台，依赖 CraftEngine。</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.11%20%2F%2026.1%20%2F%2026.2%20%2F%2026.3-3fb950" alt="Minecraft 1.21.11 / 26.1 / 26.2 / 26.3">
  <img src="https://img.shields.io/badge/CraftEngine-required-5865F2" alt="CraftEngine required">
  <img src="https://img.shields.io/badge/Java-21%20%2F%2025-orange" alt="Java 21 / 25">
  <img src="https://img.shields.io/badge/Paper%20%2F%20Folia-supported-blue" alt="Paper and Folia supported">
</p>

<p align="center">
  <a href="#功能">功能</a> · <a href="#快速上手">快速上手</a> · <a href="#命令与权限">命令与权限</a> · <a href="#配置">配置</a> · <a href="#常见问题">常见问题</a>
</p>

WoodworkingSaw 为服务端提供一个锯木台。可以将木板加工成楼梯、台阶、门、栅栏等等，可以自定义原料与产物的配置，插件依赖CraftEngine

## 功能

- 两套规则：通过本插件模型“伐木台”的转化规则；通过向CE注入的原版切石机配方
- 空手左键：打开原版切石机界面，把木板切割成楼梯、台阶、门、栅栏、告示牌、碗、箱子、船等木制品。
- 手持物品右键“伐木台”可单个切割，蹲下右键切割手持所有。转化规则与比例见 `transformations.yml`，可自定义。
- 自动上传资源包至 CraftEngine 资源包，人工执行 `/ce reload all` 以应用。

## 兼容与依赖

| 项目 | 要求 |
| --- | --- |
| 服务端 | 1.21.11 / 26.1 / 26.2 / 26.3 的 Paper、Folia |
| CraftEngine | 必需 |
| Java | 运行时 21 / 25 |

## 快速上手

1. 放入 `plugins/` 目录，重启服务端。
2. 管理员执行 `/sawtable give` 获取锯木台。
3. 按提示执行 `/ce reload all` 应用资源包。

## 配方

1. `锯木台`的合成配方（在原版工作台合成`锯木台`）：

```text
· 切石机 ·
橡木木板 橡木原木 橡木木板
铁块 铁块 铁块
```

2. 空手左键点击`锯木台`的配方：见`plugins/CraftEngine/resources/WoodworkingSaw/configuration/recipes/saw_table_cutting.yml`。
（木板 → 楼梯 / 台阶 / 门 / 栅栏 / 栅栏门 / 按钮 / 压力板 / 活板门 / 告示牌 / 碗 / 箱子 / 船）

3. 手持物品右键点击`锯木台`的配方：见 `transformations.yml`，可自定义（例如：原木去皮、木板 → 楼梯、床 → 羊毛 → 地毯 → 线、树苗 → 木棍等等）。

## 命令与权限

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/sawtable give [玩家] [数量]` | 获取锯木台 | `woodworkingsaw.give` |
| `/sawtable status` | 查看状态 | `woodworkingsaw.admin` |
| `/sawtable reload` | 重载配置与资源 | `woodworkingsaw.admin` |

别名：`/sawmill`、`/jumutai`、`/woodworking`。使用锯木台需要 `woodworkingsaw.use`（默认所有人）。

## 配置

- `plugins/WoodworkingSaw/config.yml` — 语言、潜行整组、音效、动作栏、CraftEngine 资源。
- `plugins/WoodworkingSaw/transformations.yml` — 右键转化规则（`input`/`output`/`input-amount`/`output-amount`）。
- `plugins/WoodworkingSaw/lang/` — 文案（`zh_CN.yml`、`en_US.yml`）。
- `plugins/WoodworkingSaw/resources/` — CraftEngine 资源包备份。

修改后执行 `/sawtable reload`。

## 常见问题

| 问题 | 处理 |
| --- | --- |
| 切割界面打不开 | 空手左键；若未加载执行 `/ce reload all` |
| 右键不转化 | 检查 `transformations.yml` 是否有该物品的规则 |
| 无法获取锯木台 | 执行 `/ce reload all` 后再 `/sawtable give` |
| 无法使用 | 检查 `woodworkingsaw.use` 权限 |

## 许可证

- 代码采用 GPL-3.0 开源。
- 模型与贴图文件仅供本插件使用，其它插件不得使用。
