# 食药安笺 · 药食忌口 AI 扫描器

基于 Kotlin + 豆包多模态大模型的安卓 APP，拍照识别药盒，AI 自动分析药品与食物的相克禁忌风险。

## 功能特性

- **拍照/相册双入口**：直接拍药盒或从相册选图
- **豆包 AI 智能识别**：Seed2.1 Pro 多模态模型，药盒小字识别精准
- **风险分级展示**：高风险红色高亮、低风险橙色标注，一眼识别危险组合
- **本地历史记录**：Room 数据库缓存全部扫描结果，7 天内相同药品命中缓存不重复请求
- **免责声明机制**：启动弹窗 + 底部常驻声明，严格规避医疗建议风险
- **图片自动压缩**：上传前自动压缩到 1280px，节省 token、加速请求

## 技术栈

| 模块 | 技术选型 |
|------|----------|
| 开发语言 | Kotlin |
| UI 框架 | 原生 XML + ViewBinding |
| 网络请求 | Retrofit + OkHttp |
| 图片加载 | Glide |
| 本地数据库 | Room (SQLite) |
| 异步处理 | Kotlin 协程 |
| AI 模型 | 豆包 doubao-seed-2-1-pro-260628 |
| 最低兼容 | Android 7.0 (API 24) |

## 快速开始

### 1. 获取豆包 API Key

1. 登录火山方舟控制台：https://console.volcengine.com/ark
2. 创建推理接入点，选择模型 `doubao-seed-2-1-pro-260628`
3. 复制 API Key（`sk-` 开头）

### 2. 配置项目

在项目根目录创建 `local.properties`，添加：

```properties
DOUBAO_API_KEY=你的API_KEY
sdk.dir=你的AndroidSDK路径
```

构建时会自动注入 `BuildConfig.DOUBAO_API_KEY`，代码中无需硬编码。

> `local.properties` 已加入 `.gitignore`，不会提交到版本库。

### 3. 编译运行

1. Android Studio 打开项目，等待 Gradle 同步完成
2. 连接安卓真机（开启 USB 调试）或启动模拟器
3. 点击运行按钮安装 APP

## 项目结构

```
app/src/main/
├── AndroidManifest.xml          # 权限、Activity、FileProvider 配置
├── java/com/fooddrugnote/
│   ├── MainActivity.kt          # 主页面（拍照、分析、历史）
│   ├── api/
│   │   ├── DoubaoApi.kt         # 豆包 API 封装、Prompt 构造
│   │   └── DoubaoService.kt     # Retrofit 接口 + 响应实体
│   ├── db/
│   │   ├── AppDatabase.kt       # Room 数据库单例
│   │   ├── ScanRecord.kt        # 扫描记录实体
│   │   └── ScanRecordDao.kt     # 数据访问接口
│   └── util/
│       ├── ImageUtil.kt         # 图片压缩、文件读写
│       ├── ImageCacheManager.kt # 扫描结果缓存管理
│       └── PermissionUtil.kt    # 相机/存储权限封装
└── res/
    ├── layout/
    │   ├── activity_main.xml    # 主界面布局
    │   └── item_scan_history.xml # 历史列表项
    ├── values/                  # 字符串、颜色、主题
    ├── drawable/                # 形状背景、图标
    ├── mipmap-*/                # 启动图标
    └── xml/file_paths.xml       # FileProvider 路径配置
```

## 模型切换

默认使用旗舰精准版 `doubao-seed-2-1-pro-260628`，识别准确率最高。

如果想节省 token、提升速度，可切换轻量化版本：
在 `DoubaoApi.kt` 中修改：
```kotlin
var modelId: String = MODEL_MINI  // 改为 MINI 省钱版
```

## 使用提示

1. **拍摄技巧**：正对药盒、光线充足、尽量拍清药品名称和成分表，识别更准
2. **网络要求**：需联网调用豆包 API，建议 WiFi 环境使用
3. **仅限参考**：APP 仅整理公开科普资料，不能替代医生诊断，服药请遵医嘱
4. **不上架说明**：本项目为个人自用 Demo，医疗类 APP 正式上架需要相关资质

## 常见问题

**Q: 拍照后闪退？**
A: 检查相机权限是否授予，Android 13+ 无需存储权限但相机权限必须允许。

**Q: 提示 API 请求失败 401？**
A: API Key 填错或已过期，去火山方舟控制台重新生成。

**Q: 识别结果不准确？**
A: 药盒模糊、反光会影响识别，重新拍摄清晰正面照；或切换 Pro 模型提升精度。

**Q: 可以离线使用吗？**
A: 不能，核心识别依赖豆包云端大模型，必须联网；历史记录可离线查看。

## 免责声明

本软件仅作为个人学习、技术演示用途，所有分析结果均来自大模型对公开资料的整理，
**不构成任何医疗建议**。服药、治疗相关决策请务必咨询专业医师或药师。
开发者不对因使用本软件产生的任何后果承担责任。
